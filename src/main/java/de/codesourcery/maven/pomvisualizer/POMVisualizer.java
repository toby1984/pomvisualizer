/**
 * Copyright 2016 Tobias Gierke <tobias.gierke@code-sourcery.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.codesourcery.maven.pomvisualizer;

import java.io.*;
import java.util.*;
import java.util.stream.Stream;

import javax.script.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.*;

import org.w3c.dom.*;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Tiny program that searches a directory subtree for pom.xml files 
 * and writes a dependency graph as Graphviz/DOT output to standard out.
 * 
 * <pre>
 * Usage:
 * [-v|--verbose] [-maxdepth &lt;depth&gt;] [-filter &lt;JS expression matching on variables 'groupId' and/or 'artifactId' and yielding a boolean value&gt;] &lt;folder&gt; [&lt;folder&gt;] [...] 
 * <br/>
 * <ul>
 *   <ul>-maxdepth &lt;depth&gt; =&gt; (optional) How many subdirectory levels to search for pom.xml files</ul>
 *   <ul>-filter &lt;JS expression&gt; =&gt; (optional) Javascript expression used to decide whether an artifact should be included in the output.<br/>(
 *        The expression must yield a boolean value ('true' meaning the artifact should be included in the graph) and has access to 
 *        two variables named 'groupId' and 'artifactId'.
 *   </ul>
 *   <ul>-v|--verbose =&gt; (optional) enable debug output</ul>
 *   <ul>folder =&gt; Folder where to start searching for pom.xml files</ul>
 * </ul>
 * </pre>
 * @author tobias.gierke@code-sourcery.de
 */
public class POMVisualizer
{
	private boolean verboseMode = false;

	private final Map<Coordinates,Artifact> artifacts = new HashMap<>();

	private final XPathExpression dependencyExpression;
	private final XPathExpression parentGroupIdExpression;
	private final XPathExpression projectGroupIdExpression;
	private final XPathExpression projectArtifactIdExpression;
	private final XPathExpression groupIdExpression;
	private final XPathExpression artifactIdExpression;

	@FunctionalInterface
	protected interface ThrowingConsumer<T> 
	{
		public void accept(T obj) throws Exception;
	}

	@FunctionalInterface
	protected interface DependencyFilter 
	{
		public boolean matches(Coordinates dependency);
	}

	protected static final class Coordinates 
	{
		public final String groupId;
		public final String artifactId;

		public String label;

		public Coordinates(String groupId, String artifactId) {
			this.groupId = groupId;
			this.artifactId = artifactId;
		}

		public boolean groupIdContains(String s) { return groupId.contains( s ); }
		
		public boolean artifactIdContains(String s) { return artifactId.contains( s ); }
		
		@Override
		public int hashCode() {
			int result = 31 + artifactId.hashCode();
			return 31 * result + groupId.hashCode();
		}

		@Override
		public boolean equals(Object obj) 
		{
			if ( obj instanceof Coordinates) {
				return this.groupId.equals( ((Coordinates) obj).groupId ) && this.artifactId.equals( ((Coordinates) obj).artifactId );
			}
			return false;
		}

		@Override
		public String toString() {
			return groupId+":"+artifactId;
		}
	}

	protected static final class Artifact 
	{
		public final Coordinates coordinates;
		public final Map<Coordinates,Artifact> dependencies=new HashMap<>();

		public Artifact(Coordinates key) {
			this.coordinates = key;
		}

		@Override
		public int hashCode() {
			return coordinates.hashCode();
		}

		@Override
		public boolean equals(Object obj) 
		{
			if ( obj instanceof Artifact ) {
				return this.coordinates.equals( ((Artifact) obj).coordinates );
			}
			return false;
		}
	}

	public POMVisualizer() throws XPathExpressionException 
	{
		final XPathFactory factory = XPathFactory.newInstance();
		final XPath xpath = factory.newXPath();

		dependencyExpression = xpath.compile("/project/dependencies/dependency");
		parentGroupIdExpression = xpath.compile("/project/parent/groupId");
		projectGroupIdExpression = xpath.compile("/project/groupId");
		projectArtifactIdExpression = xpath.compile("/project/artifactId");
		groupIdExpression = xpath.compile("groupId");
		artifactIdExpression = xpath.compile("artifactId");            
	}	

	public static void main(String[] args) throws Exception 
	{
		DependencyFilter filter = artifact -> true;
		final Set<File> folders = new HashSet<>();
		boolean verboseMode = false;
		int maxDepth = Integer.MAX_VALUE;
		
		final List<String> argList = Arrays.asList( args );
		for ( int i = 0 ; i < argList.size() ; i++ ) 
		{
			final String arg = argList.get(i);
			final boolean hasNextArg = (i+1) < argList.size();
			final String nextArg = hasNextArg ? argList.get(i+1) : null;
			switch( arg ) 
			{
				case "-v":
				case "--verbose":
					verboseMode = true;
					break;
				case "--help":
					System.out.println("Usage: [-v|--verbose] [-maxdepth <depth>] [-filter <JS expression matching on variables 'groupId' and/or 'artifactId' and yielding a boolean value>] <folder> [<folder>] [...]");
					return;
				case "-filter":
					if ( ! hasNextArg ) {
						throw new RuntimeException("-filter requires a parameter");
					}
					filter = new JSScriptFilter( nextArg );
					i++;
					break;					
				case "-maxdepth":
					if ( ! hasNextArg ) {
						throw new RuntimeException("-maxdepth requires a parameter");
					}
					maxDepth = Integer.parseInt( nextArg );
					i++;
					break;
				default:
					final File file = new File( arg );
					if ( folders.contains( file ) ) {
						throw new RuntimeException("Duplicate folder name: "+arg);
					}
					if ( ! file.exists() || ! file.isDirectory() ) {
						throw new RuntimeException("File "+file+" does exist or is no directory");
					}					
					folders.add( file );
			}
		}
		
		final POMVisualizer tool = new POMVisualizer();
		tool.verboseMode = verboseMode;
		tool.generateDot( folders , filter, maxDepth , System.out);
	}

	public void generateDot(Collection<File> folders,DependencyFilter filter,int maxDepth,PrintStream dotOut) throws Exception 
	{
		for ( File folder : folders ) {
			visit( folder , 0 , maxDepth , file -> 
			{ 
				if ( file.isFile() && file.getName().equals("pom.xml" ) )  
				{
					processPomXml( file , filter );
				}
			});
		}
		writeDotGraph(dotOut);
	}

	private void writeDotGraph(final PrintStream out) 
	{
		out.println("digraph {");
		int id=1;
		for ( Coordinates key : artifacts.keySet() ) {
			key.label = "label"+id;
			out.println( key.label+" [label=\""+key.toString()+"\"]");
			id++;
		}
		for ( Artifact artifact : artifacts.values() ) 
		{
			for ( Artifact dep : artifact.dependencies.values() ) 
			{
				out.println( artifact.coordinates.label +" -> "+dep.coordinates.label );
			}
		}
		out.println("}");
	}
	
	private void debug(String msg) {
		if ( verboseMode ) {
			System.out.println( msg );
		}
	}

	private void processPomXml(File pomXml,DependencyFilter filter) throws Exception 
	{
		debug("Scanning "+pomXml.getAbsolutePath()+" ...");

		final Document doc;
		try ( InputStream in = new FileInputStream( pomXml ) ) 
		{
			doc = parseXML(in);
		}

		final Optional<String> parentGroupId = evaluateXPath( parentGroupIdExpression , doc ).findFirst().map( e -> e.getTextContent() );
		debug("Parent group ID: "+parentGroupId);

		final String groupId = evaluateXPath( projectGroupIdExpression , doc ).findFirst().map( e -> e.getTextContent()).orElse( parentGroupId.get() );
		final String artifactId = evaluateXPath( projectArtifactIdExpression , doc ).findFirst().orElseThrow( RuntimeException::new ).getTextContent();

		final Coordinates key = new Coordinates(groupId,artifactId);
		if ( ! filter.matches( key ) ) {
			return;
		}
		debug("====== Got project "+key+" ======");

		final Artifact project = artifacts.computeIfAbsent( key , k -> new Artifact( k ) );

		evaluateXPath( dependencyExpression , doc ).forEach( dep -> 
		{
			final String artId = evaluateXPath( artifactIdExpression , dep ).findFirst().orElseThrow( RuntimeException::new ).getTextContent();
			final String grpId = evaluateXPath( groupIdExpression , dep ).findFirst().orElseThrow( RuntimeException::new ).getTextContent();

			final Coordinates depKey = new Coordinates(grpId,artId);
			if ( filter.matches( depKey ) ) 
			{
				debug("Found dependency "+depKey);
				final Artifact dependency = artifacts.computeIfAbsent( depKey , k -> new Artifact( k ) );
				project.dependencies.put( depKey , dependency );
			}
		});
	}

	protected static Document parseXML(InputStream inputStream) throws ParserConfigurationException, SAXException, IOException
	{
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

		final DocumentBuilder builder = factory.newDocumentBuilder();

		// set fake EntityResolver , otherwise parsing is incredibly slow (~1 sec per file on my i7)
		// because the parser will download the DTD from the internets...
		builder.setEntityResolver( new DummyResolver() );

		return builder.parse( inputStream);
	}

	private static final class DummyResolver implements EntityResolver {

		@Override
		public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException
		{
			final ByteArrayInputStream dummy = new ByteArrayInputStream(new byte[0]);
			return new InputSource(dummy);
		}
	}    

	private void visit(File folder, int currentDepth,int maxDepth , ThrowingConsumer<File> visitor) throws Exception 
	{
		if ( currentDepth > maxDepth) {
			return;
		}
		final File[] files = folder.listFiles();
		if ( files != null ) 
		{
			for ( File f : files ) 
			{
				if ( f.isDirectory() ) 
				{
					visit( f , currentDepth+1 , maxDepth , visitor );
				} else {
					visitor.accept( f );
				}
			}
		}
	}	

	private Stream<Element> evaluateXPath(XPathExpression expr, Node document)
	{
		if ( expr == null ) {
			throw new IllegalArgumentException("expression must not be NULL.");
		}
		if ( document == null ) {
			throw new IllegalArgumentException("document must not be NULL.");
		}
		final NodeList nodes;
		try {
			nodes = (NodeList) expr.evaluate(document,XPathConstants.NODESET);
		} catch (XPathExpressionException e) {
			throw new RuntimeException(e);
		}

		final List<Element> result = new ArrayList<>();
		for (int i = 0 , len = nodes.getLength() ; i < len ; i++) {
			result.add( (Element) nodes.item( i ) );
		}
		return result.stream();
	}

	protected static final class JSScriptFilter implements DependencyFilter {
		
		private final ScriptEngine engine;
		private final String jsExpr;
		
		public JSScriptFilter(String jsExpr) 
		{
			final ScriptEngineManager scriptManager = new ScriptEngineManager();
			this.engine = scriptManager.getEngineByName("nashorn");
			this.jsExpr = "var filter = function(groupId,artifactId) {\n    return "+jsExpr+";\n}";
			try 
			{
				engine.eval( this.jsExpr ); // compile our filter() function
			} catch (Exception e) {
				throw new RuntimeException("Invalid JS expression: \n"+jsExpr,e);
			}
		}

		@Override
		public boolean matches(Coordinates dependency) 
		{
			final Object result; 
			try {
				result = ((Invocable) this.engine).invokeFunction( "filter" , dependency.groupId , dependency.artifactId );
			} catch (NoSuchMethodException | ScriptException e) {
				throw new RuntimeException("Invalid JS expression: \n"+jsExpr,e);
			}
			if ( result ==null || ! (result instanceof Boolean) ) {
				throw new RuntimeException("Invalid JS expression (didn't yield a boolean result but "+result+"): \n"+jsExpr);
			}
			return ((Boolean) result).booleanValue();
		}
	}
}