/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.tools.opensource.dependencies;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;

/**
 * Based on the <a href="https://maven.apache.org/resolver/index.html">Apache Maven Artifact
 * Resolver</a> (formerly known as Eclipse Aether).
 */
public class DependencyGraphBuilder {
  
  private static final RepositorySystem system = RepositoryUtility.newRepositorySystem();
  
  static {
    // os.detected.classifier system property used to select Netty deps
    String OS = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
    if ((OS.indexOf("mac") >= 0) || (OS.indexOf("darwin") >= 0)) {
      System.setProperty("os.detected.classifier", "osx-x86_64");
    } else if (OS.indexOf("win") >= 0) {
      System.setProperty("os.detected.classifier", "windows-x86_64");
    } else if (OS.indexOf("nux") >= 0) {
      System.setProperty("os.detected.classifier", "linux-x86_64");
    } else {
      // Since we only load the dependency graph, not actually use the 
      // dependency, it doesn't matter a great deal which one we pick.
      System.setProperty("os.detected.classifier", "linux-x86_64");      
    }
  }

  // caching cuts time by about a factor of 4.
  private static final Map<String, DependencyNode> cache = new HashMap<>();

  private static DependencyNode resolveCompileTimeDependencies(Artifact artifact)
      throws DependencyCollectionException, DependencyResolutionException {
    
    String key = Artifacts.toCoordinates(artifact);
    if (cache.containsKey(key)) {
      return cache.get(key);
    }
    
    RepositorySystemSession session = RepositoryUtility.newSession(system);

    Dependency dependency = new Dependency(artifact, "compile");

    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRoot(dependency);
    collectRequest.addRepository(RepositoryUtility.CENTRAL);
    DependencyNode node = system.collectDependencies(session, collectRequest).getRoot();

    DependencyRequest dependencyRequest = new DependencyRequest();
    dependencyRequest.setRoot(node);
    // TODO might be able to speed up by using collectDependencies here instead
    system.resolveDependencies(session, dependencyRequest);
    cache.put(key, node);
    
    return node;
  }

  /**
   * Returns the non-transitive compile time dependencies of an artifact.
   */
  public static List<Artifact> getDirectDependencies(Artifact artifact)
      throws DependencyCollectionException, DependencyResolutionException {
    
    List<Artifact> result = new ArrayList<>();
    
    DependencyNode node = resolveCompileTimeDependencies(artifact);
    for (DependencyNode child : node.getChildren()) {
      result.add(child.getArtifact());
    }
    return result;
  }
  
  /**
   * Finds the full compile time, transitive dependency graph including duplicates
   * and conflicting versions.
   */
  public static DependencyGraph getCompleteDependencies(Artifact artifact)
      throws DependencyCollectionException, DependencyResolutionException {
    
    // root node
    DependencyNode node = resolveCompileTimeDependencies(artifact);  
    DependencyGraph graph = new DependencyGraph();
    fullPreorder(new Stack<>(), node, graph);
    
    return graph;
  }
  
  /**
   * Finds the complete transitive dependency graph as seen by Maven.
   * It does not include duplicates and conflicting versions. That is,
   * this resolves conflicting versions by picking the first version
   * seen. This is how Maven normally operates.
   */
  public static DependencyGraph getTransitiveDependencies(Artifact artifact)
      throws DependencyCollectionException, DependencyResolutionException {
    
    // root node
    DependencyNode node = resolveCompileTimeDependencies(artifact);  
    DependencyGraph graph = new DependencyGraph();
    preorder(new Stack<>(), node, graph);
    
    return graph;
  }
  
  // TODO Dedup the next two methods. They are duplicate code with only one line difference.
  // this finds the actual graph that Maven sees with no duplicates and at most one version per
  // library.
  @SuppressWarnings("unchecked")
  private static void preorder(Stack<DependencyNode> path, DependencyNode current,
      DependencyGraph graph) {
    
    path.push(current);
    DependencyPath forPath = new DependencyPath();
    for (DependencyNode node : path) {
      if (!"system".equals(node.getDependency().getScope())) {
        forPath.add(node.getArtifact());
      }
    }
    graph.addPath(forPath);
    
    for (DependencyNode child : current.getChildren()) {
      preorder((Stack<DependencyNode>) path.clone(), child, graph);
    }
  }

  @SuppressWarnings("unchecked")
  private static void fullPreorder(Stack<DependencyNode> path, DependencyNode current,
      DependencyGraph graph) throws DependencyCollectionException, DependencyResolutionException {
    
    path.push(current);
    
    DependencyPath forPath = new DependencyPath();
    for (DependencyNode node : path) {
      if (!"system".equals(node.getDependency().getScope())) {
        forPath.add(node.getArtifact());
      }
    }
    graph.addPath(forPath);
    
    for (DependencyNode child : current.getChildren()) {
      if (!"system".equals(child.getDependency().getScope())) {
        try {
          child = resolveCompileTimeDependencies(child.getArtifact());
          // somehow we've got an infinite recursion here
          // requires equals
          if (path.contains(child)) {
            System.err.println("Infinite recursion resolving " + current);
            System.err.println("Likely cycle in " + forPath);              
            System.err.println("Child " + child);
          } else {
            fullPreorder((Stack<DependencyNode>) path.clone(), child, graph);
          }
        } catch (DependencyResolutionException ex) {
          System.err.println("Error resolving " + forPath);
          System.err.println(ex.getMessage());
          throw ex;
        }
      }
    }
  }

}
