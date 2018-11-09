/**
 * Copyright 2011-2017 GatlingCorp (http://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.mojo;

import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MojoUtils {

  public static boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

  private MojoUtils() {
  }

  public static String locateJar(Class<?> c) throws Exception {
    final URL location;
    final String classLocation = c.getName().replace('.', '/') + ".class";
    final ClassLoader loader = c.getClassLoader();
    if (loader == null) {
      location = ClassLoader.getSystemResource(classLocation);
    } else {
      location = loader.getResource(classLocation);
    }
    if (location != null) {
      Pattern p = Pattern.compile("^.*file:(.*)!.*$");
      Matcher m = p.matcher(location.toString());
      if (m.find()) {
        return URLDecoder.decode(m.group(1), "UTF-8");
      }
      throw new ClassNotFoundException("Cannot parse location of '" + location + "'.  Probably not loaded from a Jar");
    }
    throw new ClassNotFoundException("Cannot find class '" + c.getName() + " using the classloader");
  }

  public static <T> List<T> arrayAsListEmptyIfNull(T[] array) {
    return array == null ? Collections.emptyList() : Arrays.asList(array);
  }


  /**
   * Create a jar with just a manifest containing a Main-Class entry for BooterConfiguration and a Class-Path entry
   * for all classpath elements.
   *
   * @param classPath      List of all classpath elements.
   * @param startClassName The classname to start (main-class)
   * @return The file pointing to the jar
   * @throws java.io.IOException When a file operation fails.
   */
  public static File createBooterJar(List<String> classPath, String startClassName, Log logger) throws IOException {
    File file = File.createTempFile("gatlingbooter", ".jar");
    file.deleteOnExit();

    Manifest manifest = new Manifest();

    Path parent = file.getParentFile().getCanonicalFile().toPath();

    manifest.getMainAttributes().putValue(Attributes.Name.MANIFEST_VERSION.toString(), "1.0");
    // JAR spec requires relative urls
    manifest.getMainAttributes().putValue(Attributes.Name.CLASS_PATH.toString(), serializeClasspath(classPath, classPathElement -> toRelativeClasspathElementUri(parent, classPathElement, logger)));
    // but it such a pita do deal with in ZincCompiler, so lets duplicate
    manifest.getMainAttributes().putValue("Absolute-" + Attributes.Name.CLASS_PATH, serializeClasspath(classPath, classPathElement -> toAbsoluteClasspathElementUri(classPathElement)));
    manifest.getMainAttributes().putValue(Attributes.Name.MAIN_CLASS.toString(), startClassName);

    try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(file))) {
      jos.setLevel(JarOutputStream.STORED);
      JarEntry je = new JarEntry("META-INF/MANIFEST.MF");
      jos.putNextEntry(je);
      manifest.write(jos);
    }

    return file;
  }

  private static String serializeClasspath(List<String> classPath, Function<File, String> toUri) {
    StringBuilder cp = new StringBuilder();
    for (String el : classPath) {
      File classPathElement = new File(el);
      String uri = toUri.apply(classPathElement);
      cp.append(uri);
      // NOTE: if File points to a directory, this entry MUST end in '/'.
      if (classPathElement.isDirectory() && !uri.endsWith("/")) {
        cp.append('/');
      }

      cp.append(' ');
    }

    cp.setLength(cp.length() - 1);

    return cp.toString();
  }

  private static String toAbsoluteClasspathElementUri(File classPathElement) {
    return classPathElement.toURI().toASCIIString();
  }

  private static String toRelativeClasspathElementUri(Path parent, File classPathElement, Log logger) {
    try {
      return new URI(null, parent.relativize(classPathElement.toPath()).toString(), null).toASCIIString();
    } catch (IllegalArgumentException e) {
      logger.error("Boot Manifest-JAR contains absolute paths in classpath " + classPathElement.getPath());
      return classPathElement.toURI().toASCIIString();
    } catch (URISyntaxException e) {
      // This is really unexpected, so fail
      throw new IllegalArgumentException("Could not create a relative path " + classPathElement + " against " + parent, e);
    }
  }
}
