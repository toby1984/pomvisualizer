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

import de.codesourcery.maven.pomvisualizer.POMVisualizer.Artifact;
import de.codesourcery.maven.pomvisualizer.POMVisualizer.IterationContext;

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

    protected interface DependencyFilter 
    {
        public boolean matches(Coordinates dependency);

        public boolean matches(Artifact artifact);
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

        public boolean matches(String groupId,String artifactId) 
        {
            return Objects.equals( this.groupId , groupId ) &&
                    Objects.equals( this.artifactId ,  artifactId );
        }

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

    public interface Visitor<T> 
    {
        public void visit(IterationContext<T> context,Artifact artifact);
    }

    public static final class IterationContext<T> 
    {
        public boolean stop;
        public T result;

        public IterationContext(T defaultValue) {
            this.result = defaultValue;
        }

        public void stop(T result) {
            this.stop = true;
            this.result = result;
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

        /**
         * Returns whether this artifact directly or indirectly depends on the given coordinate.
         * @param groupId 
         * @param artifactId 
         * @return
         */		
        public boolean dependsOn(String groupId,String artifactId) 
        {
            System.out.println("dependsOn( "+groupId+" , "+artifactId+ " )");
            return visitAll( (ctx,artifact) -> 
            {
                if ( artifact.coordinates.matches(groupId,artifactId) ) {
                    ctx.stop( true );
                }
            }, false );
        }
        
        @Override
        public String toString()
        {
            return coordinates.toString();
        }

        /**
         * Returns whether this artifact directly or indirectly depends on the given coordinate.
         * 
         * @param coords
         * @return
         */
        public boolean dependsOn(Coordinates coords) 
        {
            return visitAll( (ctx,artifact) -> 
            {
                if ( artifact.coordinates.equals( coords ) ) {
                    ctx.stop( true );
                }
            }, false );
        }

        public <T> T visitAll(Visitor<T> visitor,T defaultValue) 
        {
            final Set<Artifact> visited = new HashSet<>();
            final IterationContext<T> ctx = new IterationContext<T>(defaultValue);
            final Stack<Artifact> toVisit=new Stack<>();
            toVisit.push( this );
            while ( ! toVisit.isEmpty() ) 
            {
                final Artifact current = toVisit.pop();
                if ( ! visited.contains( current ) ) 
                {
                    visited.add( current );                    
                    visitor.visit( ctx, current );
                    if ( ctx.stop ) {
                        break;
                    }                    
                    toVisit.addAll( current.dependencies.values() );
                }
            }
            return ctx.result;
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
        final Set<File> folders = new HashSet<>();
        boolean verboseMode = false;
        int maxDepth = Integer.MAX_VALUE;
        String dependencyFilterExpr = "true";
        String artifactFilterExpr = "true";

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
                    System.out.println("Usage: [-v|--verbose] [-maxdepth <depth>] [-artifactfilter <JS expression matching on variable 'artifact' and yielding a boolean value>] [-coordinatesfilter <JS expression matching on variables 'groupId' and/or 'artifactId' and yielding a boolean value>] <folder> [<folder>] [...]");
                    return;
                case "-coordinatesfilter":
                    if ( ! hasNextArg ) {
                        throw new RuntimeException("-coordinatesfilter requires a parameter");
                    }
                    dependencyFilterExpr = nextArg;
                    i++;
                    break;	
                case "-artifactfilter":
                    if ( ! hasNextArg ) {
                        throw new RuntimeException("-artifactfilter requires a parameter");
                    }
                    artifactFilterExpr = nextArg;
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

        final DependencyFilter filter = new JSScriptFilter( dependencyFilterExpr , artifactFilterExpr );
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

        // apply artifact-level filter
        debug("Applying artifact-level filter...");
        Set<Artifact> toRemove = new HashSet<>();
        for ( Artifact a : artifacts.values() ) {
            if ( ! filter.matches( a ) ) {
                debug("Not matched by artifact-level filter: "+a);
                toRemove.add( a );
            }
        }
        for ( Artifact a : artifacts.values() ) 
        {
            a.dependencies.values().removeAll( toRemove );
        }
        artifacts.values().removeAll( toRemove );

        // render graph
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

        Optional<String> grpIdAsString = evaluateXPath( projectGroupIdExpression , doc ).findFirst().map( e -> e.getTextContent() );
        if ( ! grpIdAsString.isPresent() ) {
            grpIdAsString = parentGroupId;
        }
        final String groupId = grpIdAsString.get();
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

        private final ScriptEngine jsEngine;
        public final String jsDependencyExpr;
        public final String jsArtifactExpr;

        private JSScriptFilter(String jsDependencyExpr,String jsArtifactExpr) 
        {
            final ScriptEngineManager scriptManager = new ScriptEngineManager();
            this.jsEngine = scriptManager.getEngineByName("nashorn");

            this.jsArtifactExpr = "var artifactFilter = function(groupId,artifactId) {\n    return "+jsArtifactExpr+";\n}";
            this.jsDependencyExpr = "var dependencyFilter = function(groupId,artifactId) {\n    return "+jsDependencyExpr+";\n}";

            // artifact expression
            try 
            {
                jsEngine.eval( this.jsArtifactExpr );
            } 
            catch (Exception e) {
                throw new RuntimeException("Invalid JS dependency expression: \n"+jsDependencyExpr,e);
            }		    

            // dependency expression
            try 
            {
                jsEngine.eval( this.jsDependencyExpr );
            } 
            catch (Exception e) {
                throw new RuntimeException("Invalid JS dependency expression: \n"+jsDependencyExpr,e);
            }
        }

        @Override
        public boolean matches(Artifact artifact)
        {
            final Visitor<Boolean> visitor = (context, artifact1) ->
            {
                final Boolean result = invokeFilterFunc( "artifactFilter" , artifact1.coordinates.groupId, artifact1.coordinates.artifactId );
                if ( result.booleanValue() ) 
                {
                    context.stop( true );
                }
            }; 
            return artifact.visitAll( visitor , false );
        }

        @Override
        public boolean matches(Coordinates dependency) 
        {
            return invokeFilterFunc("dependencyFilter", dependency.groupId, dependency.artifactId );
        }        
        
        private Boolean invokeFilterFunc(String functionName,Object... arguments) 
        {
            final String expr = "artifactFilter".equals( functionName ) ? jsArtifactExpr : jsDependencyExpr;
            final Object result; 
            try {
                result = ((Invocable) this.jsEngine).invokeFunction( functionName , arguments );
            } 
            catch (NoSuchMethodException | ScriptException e) 
            {
                throw new RuntimeException("Invalid JS artifact expression: \n"+expr,e);
            }
            if ( result ==null || ! (result instanceof Boolean) ) {
                throw new RuntimeException("Invalid JS artifact expression (didn't yield a boolean result but "+result+"): \n"+expr);
            }
            return ((Boolean) result).booleanValue();
        }
    }
}