__POM visualizer__

A tiny self-executable Java program that searches a directory subtree for Maven pom.xml files and writes a dependency graph as Graphviz/DOT output to standard out. The program will look for cycles in the graph and color the edges of the shortest cycles in red.

![screenshot](https://github.com/toby1984/pomvisualizer/blob/master/screenshot.png?raw=true)

__Requirements__

GraphViz (http://www.graphviz.org/) , JDK 1.8 and Maven 3.x

__Building__

You can create a self-executable JAR /target/pomvisualizer.jar by running

```maven clean package```

__Running__

You can run the program using

```java -jar target/pomvisualizer.jar [-v|--verbose] [-maxdepth <depth>] [-filter <JS filter expression>] <folder name> [<folder name> [...]```

__Options__

* -maxdepth &lt;depth&gt; => (optional) How many subdirectory levels to search for pom.xml files
* -filter &lt;JS expression&gt; => (optional) Javascript expression used to decide whether an artifact should be included in the output. The expression must yield a boolean value ('true' meaning the artifact should be included in the graph).
* -v|--verbose => (optional) enable debug output
* folder name => One or more folders where to look for pom.xml files

You may want to redirect the output to a file for later processing by dot/neato or directly pipe it into dot.

__Javascript expressions__

The filter expression has the de.code-sourcery.maven.pomvisualizer.POMVisualizer.Artifact class bound to the global scope as variable 'artifact'. You can access the group ID via

```
artifact.groupId()
```

, the artifact ID via 

```
artifact.artifactId()
```

,check whether the artifact directly or indirectly depends on a given groupId/artifactId combination by using the expression 

```
artifact.dependsOn( "<group id>" , "<artifact id>")
```

and check whether the artifact is directly or indirectly required by something by doing

```
artifact.requiredBy("<group id>","<artifact id>")
```

See the Artifact class for more fields/methods that might be accessible.
