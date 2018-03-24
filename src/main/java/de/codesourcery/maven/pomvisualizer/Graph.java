package de.codesourcery.maven.pomvisualizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import de.codesourcery.maven.pomvisualizer.POMVisualizer.Artifact;

public class Graph 
{
    private final Map<Artifact,Integer> nodesToIndex=new HashMap<>();
    public final Artifact[] nodes;
    private final int[][] dependsOn;
    private final int edgeCount;
    
    public static final Graph EMPTY = new Graph( new Artifact[0], new int[0][] );

    private Graph(Artifact[] nodes, int[][] dependencies) {
        this.nodes = nodes;
        this.dependsOn = dependencies;
        int idx = 0;
        for ( Artifact a : nodes ) 
        {
            nodesToIndex.put( a , idx++ );
        }
        int sum = 0;
        for ( int[] edges : this.dependsOn ) 
        {
            sum += edges.length;
        }
        this.edgeCount = sum;
    }
    
    public boolean isEmpty() {
        return nodes.length==0;
    }

    public static GraphBuilder newGraph() {
        return new GraphBuilder();
    }

    public int indexOf(Artifact a) {
        return nodesToIndex.get(a);
    }

    public List<Artifact> getDependencies(Artifact a) 
    {
        final int idx = indexOf(a);
        final int[] list = dependsOn[idx];
        final List<Artifact> result = new ArrayList<>(list.length);
        for ( int x : list ) 
        {
            result.add( nodes[x] );
        }
        return result;
    }    
    
    public Graph removeAll(Collection<Artifact> toRemove) 
    {
        final List<Artifact> remainingNodes = new ArrayList<>();
        int nodeIdx = 0;
        final Map<Artifact,Integer> remainingToIdx = new HashMap<>();
        final Map<Integer,Integer> oldIdxToNewIdx = new HashMap<>();
        for ( int idx = 0 ; idx < nodes.length ; idx++ ) 
        {
            final Artifact a = nodes[idx ];
            if ( ! toRemove.contains( a ) ) {
                remainingNodes.add( a );
                remainingToIdx.put( a , nodeIdx);
                oldIdxToNewIdx.put( idx, nodeIdx );
                nodeIdx++;
            }
        }
        final int[][] newDependencies = new int[remainingNodes.size()][];
        for ( int i = 0 ; i < dependsOn.length ; i++ ) 
        {
            if ( remainingNodes.contains( nodes[i] ) ) 
            {
                final int[] list = dependsOn[i];
                final int newIdx = oldIdxToNewIdx.get(i);
                final int[] newList = new int[list.length];
                newDependencies[newIdx] = newList;
                for ( int ptr = 0 ; ptr < list.length ; ptr++) {
                    newList[ptr] = oldIdxToNewIdx.get( list[ptr] );
                }
            }
        }
        return new Graph(remainingNodes.toArray( new Artifact[0] ) , newDependencies );
    }

    public List<Artifact> getRequiredBy(Artifact a) 
    {
        final List<Artifact> result = new ArrayList<>();
        final int idx = indexOf(a);
        int listIdx = 0;
        for ( int[] list : dependsOn ) 
        {
            if ( contains(list,idx) ) {
                result.add( nodes[listIdx ] );
            }
            listIdx++;
        }
        return result;
    }

    private static boolean contains(int[] list,int element) 
    {
        for (int i = 0,len=list.length; i < len; i++) {
            if ( list[i] == element ) {
                return true;
            }
        }
        return false;
    }

    public static final class GraphBuilder 
    {
        private int nodeIdx = 0;
        private final Map<Artifact,Integer> nodes=new HashMap<>();
        private final Map<Integer,List<Integer>> dependsOn=new HashMap<>();

        public GraphBuilder addNode(Artifact a) 
        {
            if ( ! nodes.containsKey( a ) ) {
                nodes.put( a, nodeIdx++);
            }
            return this;
        }

        public Graph build() 
        {
            final Artifact[] nodeArray = new Artifact[ nodes.size() ];
            for ( Entry<Artifact, Integer> entry : nodes.entrySet() ) 
            {
                nodeArray[entry.getValue()]=entry.getKey();
            }
            final int[][] depArray = new int[ dependsOn.size() ][];
            for ( Entry<Integer, List<Integer>> entry : dependsOn.entrySet() ) {
                depArray[ entry.getKey() ] = entry.getValue().stream().mapToInt( Integer::intValue ).toArray();
            }
            return new Graph(nodeArray,depArray);
        }

        public GraphBuilder addDependency(Artifact start, Artifact end) {
            assertContains(start);
            assertContains(end);
            final int startIdx = nodes.get(start);
            final int endIdx = nodes.get(end);
            List<Integer> list = dependsOn.get( startIdx );
            if ( list == null ) {
                list = new ArrayList<>();
                dependsOn.put( startIdx, list );
            }
            if ( ! list.contains( endIdx ) ) {
                list.add( endIdx );
            }
            return this;
        }

        private void assertContains(Artifact a) {
            if ( ! nodes.containsKey( a ) ) {
                throw new IllegalStateException("artifact "+a+" not added yet?");
            }
        }
    }
    
    public int edgeCount() {
        return edgeCount;
    }
    
    public boolean hasEdge(Artifact a,Artifact b) {
        final int start = indexOf(a);
        final int end = indexOf(b);
        final int[] list = this.dependsOn[start];
        return contains(list,end);
    }
    
    public boolean haveSameNodes(Graph other) 
    {
        if ( this.nodeCount() == other.nodeCount() ) 
        {
            for ( int idx = 0 ; idx < this.nodes.length ; idx++) {
                Artifact x = this.nodes[idx];
                if ( ! other.contains( x ) ) {
                    return false;
                }
            }
        }
        return false;
    }
    
    public boolean equals(Graph other) 
    {
        if ( this.nodeCount() == other.nodeCount() && this.edgeCount() == other.edgeCount() ) 
        {
            for ( int idx = 0 ; idx < this.nodes.length ; idx++) {
                Artifact x = this.nodes[idx];
                if ( ! other.nodesToIndex.containsKey( x ) ) {
                    return false;
                }
                final int[] list = dependsOn[idx];
                if ( list != null ) 
                {
                    for ( int dependency : list ) {
                        if ( ! other.hasEdge(x, nodes[dependency] ) ) {
                            return false;
                        }
                    }
                }
            }
        }
        return false;
    }
    
    public boolean contains(Artifact x) {
        return nodesToIndex.containsKey( x );
    }
    
    public boolean intersects(Graph other) 
    {
        Graph smaller = this.nodeCount() < other.nodeCount() ? this : other;
        Graph larger = smaller == this ? other : this;
        
        for ( Artifact n : smaller.nodes ) {
            if ( larger.contains( n ) ) {
                return true;
            }
        }
        return false;
    }
    
    public int nodeCount() {
        return nodes.length;
    }
    
    private void debug(String msg) {
        System.out.println(msg);
    }

    /**
     * Returns the shortest cycle involving a given artifact
     * @param a
     * @return
     */
    public Graph getShortestCycle(Artifact a) 
    {
        final int[] parents = new int[nodes.length];
        Arrays.fill(parents,-1); // TODO: performance, remove this (see below)
        
        final boolean[] visited = new boolean[nodes.length];
        final LinkedList<Integer> queue = new LinkedList<>();
        
        final int startIdx = indexOf( a );
        queue.add( startIdx );
        visited[startIdx] = true;
        
        debug("Looking for cycles involving: "+a);
        while ( ! queue.isEmpty() ) 
        {
            final Integer parent = queue.remove(0);
            debug("==== Parent: "+nodes[parent]);
            for ( int c : dependsOn[parent] ) 
            {
                debug("Child: "+nodes[c]);
                if ( c == startIdx ) 
                {
                    debug("Found cycle: "+nodes[c]);
                    final LinkedList<Integer> path = new LinkedList<>();
                    int current = parent;
                    do 
                    {
                        path.add(current);
                        current = parents[current];
                    } while ( current != -1 ); // TODO: Optimization opportunity, get rid of Array.fill() and check current == startIdx instead
                    Collections.reverse( path );
                    final GraphBuilder builder = Graph.newGraph();
                    for ( Integer key : path ) {
                        builder.addNode( nodes[key] );
                    }
                    for ( int i = 0,len=path.size()-1 ; i < len ; i++ ) {
                        builder.addDependency( nodes[ path.get(i) ] , nodes[ path.get(i+1) ] );
                    }
                    // close cycle
                    builder.addDependency( nodes[ path.get( path.size()-1 ) ] , a );
                    return builder.build();
                }           
                if ( visited[c] == false ) 
                {
                    parents[c] = parent;
                    visited[c]=true;
                    queue.add( c );
                }
            }
        }
        return EMPTY;
    }    
}