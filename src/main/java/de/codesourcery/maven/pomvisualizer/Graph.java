package de.codesourcery.maven.pomvisualizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.codesourcery.maven.pomvisualizer.POMVisualizer.Artifact;

public class Graph 
{
	private final Artifact[] nodes;
	private final int[] edges;
	
	public static final class Edge {
		final Artifact start;
		final Artifact end;
		
		public Edge(Artifact start, Artifact end) {
			this.start = start;
			this.end = end;
		}		
	}
	
	private Graph(Artifact[] nodes, int[] edges) {
		this.nodes = nodes;
		this.edges = edges;
	}

	public static GraphBuilder newGraph() {
		return new GraphBuilder();
	}
	
	public static final class GraphBuilder 
	{
		private final List<Artifact> nodes=new ArrayList<>();
		private final Set<Edge> edges=new HashSet<>();
	
		public GraphBuilder addNode(Artifact a) {
			this.nodes.add(a);
			return this;
		}
		
		public GraphBuilder addEdge(Artifact start, Artifact end) {
			assertContains(start);
			assertContains(end);
			edges.add( new Edge(start,end));
			return this;
		}

		private void assertContains(Artifact a) {
			if ( ! nodes.contains( a ) ) {
				throw new IllegalStateException("artifact "+a+" not registered yet");
			}
		}
		
	}
	
}
