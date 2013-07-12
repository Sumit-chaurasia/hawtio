/**
 * Copyright (C) 2013 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
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
package io.hawt.introspect;

import io.hawt.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;


/**
 * A helper class to scan classes on the classpath
 */
public class ClassScanner {
    private static final transient Logger LOG = LoggerFactory.getLogger(ClassScanner.class);

    private final ClassLoader[] classLoaders;


    public static ClassScanner newInstance() {
        return new ClassScanner(Thread.currentThread().getContextClassLoader(), ClassScanner.class.getClassLoader());
    }

    public ClassScanner(ClassLoader... classLoaders) {
        this.classLoaders = classLoaders;
    }


    /**
     * Searches for the available class names given the text search
     *
     * @return all the class names found on the current classpath using the given text search filter
     */
    public SortedSet<String> findClassNames(String search, Integer limit) {
        return findClassNamesInPackages(search, limit, Package.getPackages());
    }

    /**
     * Returns all the class names found on the classpath in the given packages which match the given filter
     */
    public SortedSet<String> findClassNamesInPackages(String search, Integer limit, Package... packages) {
        SortedSet<String> answer = new TreeSet<String>();
        Set<Class<?>> classes = new HashSet<Class<?>>();
        Map<String, ClassResource> urlSet = new HashMap<String, ClassResource>();
        for (Package aPackage : packages) {
            addPackageResources(aPackage, urlSet);
        }
        for (ClassResource classResource : urlSet.values()) {
            addClassesForPackage(classResource, search, limit, classes);
        }
        for (Class<?> aClass : classes) {
            answer.add(aClass.getName());
        }
        return answer;
    }

    /**
     * Returns all the classes found in a sorted map
     */
    public SortedMap<String, Class<?>> getAllClassesMap() {
        Package[] packages = Package.getPackages();
        return getClassesMap(packages);
    }

    /**
     * Returns all the classes found in a sorted map for the given list of packages
     */
    public SortedMap<String, Class<?>> getClassesMap(Package... packages) {
        SortedMap<String, Class<?>> answer = new TreeMap<String, Class<?>>();
        Map<String, ClassResource> urlSet = new HashMap<String, ClassResource>();
        for (Package aPackage : packages) {
            addPackageResources(aPackage, urlSet);
        }
        for (ClassResource classResource : urlSet.values()) {
            Set<Class<?>> classes = getClassesForPackage(classResource, null, null);
            for (Class<?> aClass : classes) {
                answer.put(aClass.getName(), aClass);
            }
        }
        return answer;
    }


    public Set<Class<?>> getClassesForPackage(ClassResource classResource, String filter, Integer limit) {
        Set<Class<?>> classes = new HashSet<Class<?>>();
        addClassesForPackage(classResource, filter, limit, classes);
        return classes;
    }


    /**
     * Finds a class from its name
     */
    public Class<?> findClass(String className) throws ClassNotFoundException {
        for (ClassLoader classLoader : classLoaders) {
            try {
                return classLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                // ignore
            }
        }
        return Class.forName(className);
    }

    // Implementation methods
    //-------------------------------------------------------------------------
    protected void addPackageResources(Package aPackage, Map<String, ClassResource> urlSet) {
        String packageName = aPackage.getName();
        String relativePath = getPackageRelativePath(packageName);
        List<URL> resources = getResources(relativePath,
                Thread.currentThread().getContextClassLoader(),
                ClassScanner.class.getClassLoader());
        for (URL resource : resources) {
            String key = getJavaResourceKey(resource);
            urlSet.put(key, new ClassResource(packageName, resource));
        }
    }

    protected void addClassesForPackage(ClassResource classResource, String filter, Integer limit, Set<Class<?>> classes) {
        String packageName = classResource.getPackageName();
        URL resource = classResource.getResource();
        if (resource != null && withinLimit(limit, classes)) {
            String resourceText = resource.toString();
            LOG.debug("Searching resource " + resource);
            if (resourceText.startsWith("jar:")) {
                processJar(classResource, classes, filter, limit);
            } else {
                processDirectory(new File(resource.getPath()), packageName, classes, filter, limit);
            }
        }
    }
    protected void processDirectory(File directory, String packageName, Set<Class<?>> classes, String filter, Integer limit) {
        String[] fileNames = directory.list();
        for (String fileName : fileNames) {
            if (!withinLimit(limit, classes)) {
                return;
            }
            String className = null;
            String packagePrefix = Strings.isNotBlank(packageName) ? packageName + '.' : packageName;
            if (fileName.endsWith(".class")) {
                className = packagePrefix + fileName.substring(0, fileName.length() - 6);
            }
            Class<?> aClass = tryFindClass(className, filter);
            if (aClass != null) {
                classes.add(aClass);
            }
            File subdir = new File(directory, fileName);
            if (subdir.isDirectory()) {
                processDirectory(subdir, packagePrefix + fileName, classes, filter, limit);
            }
        }
    }

    protected void processJar(ClassResource classResource, Set<Class<?>> classes, String filter, Integer limit) {
        URL resource = classResource.getResource();
        String packageName = classResource.getPackageName();
        String relativePath = getPackageRelativePath(packageName);
        String jarPath = getJarPath(resource);
        JarFile jarFile;
        try {
            jarFile = new JarFile(jarPath);
        } catch (IOException e) {
            LOG.debug("IOException reading JAR '" + jarPath + ". Reason: " + e, e);
            return;
        }
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements() && withinLimit(limit, classes)) {
            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();
            String className = null;
            if (entryName.endsWith(".class") && entryName.startsWith(relativePath) && entryName.length() > (relativePath.length() + 1)) {
                className = entryName.replace('/', '.').replace('\\', '.').replace(".class", "");
            }
            Class<?> aClass = tryFindClass(className, filter);
            if (aClass != null) {
                classes.add(aClass);
            }
        }
    }

    protected String getJavaResourceKey(URL resource) {
        String resourceText = resource.toString();
        if (resourceText.startsWith("jar:")) {
            return "jar:" + getJarPath(resource);
        } else {
            return resource.getPath();
        }
    }


    private String getJarPath(URL resource) {
        String resourcePath = resource.getPath();
        return resourcePath.replaceFirst("[.]jar[!].*", ".jar").replaceFirst("file:", "");
    }

    protected Class<?> tryFindClass(String className, String filter) {
        Class<?> aClass = null;
        if (Strings.isNotBlank(className) && classNameMatches(className, filter)) {
            try {
                aClass = findClass(className);
            } catch (Throwable e) {
                LOG.debug("Could not load class " + className + ". " + e, e);
            }
        }
        return aClass;
    }

    protected List<URL> getResources(String relPath, ClassLoader... classLoaders) {
        List<URL> answer = new ArrayList<URL>();
        for (ClassLoader classLoader : classLoaders) {
            try {
                Enumeration<URL> resources = classLoader.getResources(relPath);
                while (resources.hasMoreElements()) {
                    URL url = resources.nextElement();
                    if (url != null) {
                        answer.add(url);
                    }
                }
            } catch (IOException e) {
                LOG.warn("Failed to load resources for path " + relPath + " from class loader " + classLoader + ". Reason:  " + e, e);
            }
        }
        return answer;
    }


    /**
     * Returns true if the given class name matches the filter search
     */
    protected boolean classNameMatches(String className, String search) {
        return className.contains(search);
    }

    protected String getPackageRelativePath(String packageName) {
        return packageName.replace('.', '/');
    }

    /**
     * Returns true if we are within the limit value for the number of found classes
     */
    protected boolean withinLimit(Integer limit, Collection<Class<?>> classes) {
        if (limit == null) {
            return true;
        } else {
            int value = limit.intValue();
            return value <= 0 || value > classes.size();
        }
    }
}