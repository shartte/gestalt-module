/*
 * Copyright 2014 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.module.sandbox;

import com.google.common.collect.ImmutableList;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.naming.Name;

import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import java.util.List;

/**
 * A classloader to use when loading modules. This classloader ties into the sandboxing of modules by:
 * <ul>
 * <li>Acting as an indicator that a class belongs to a module - any class whose classloader is an instance of ModuleClassLoader comes from a module.</li>
 * <li>Restricting the classes visible to modules to those registered as API classes or belonging to an API package. Accessing any other class outside of the
 * modules results in a ClassNotFoundException</li>
 * </ul>
 * <p/>
 * Additionally, the ModuleClassLoader provides hooks for any injection that needs to be done to module classes as they are loaded, via javassist.
 *
 * @author Immortius
 */
public class ModuleClassLoader extends URLClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(ModuleClassLoader.class);
    private final APIProvider apiProvider;
    private final ClassPool pool;

    private final Name moduleId;
    private final List<BytecodeInjector> bytecodeInjectors;

    /**
     * @param urls        The urls where the module classes can be found
     * @param parent      The parent classloader, where the API classes can be found
     * @param apiProvider The security manager that sandboxes the classes
     */
    public ModuleClassLoader(Name module, URL[] urls, ClassLoader parent, APIProvider apiProvider) {
        this(module, urls, parent, apiProvider, Collections.<BytecodeInjector>emptyList());
    }

    /**
     * @param urls        The urls where the module classes can be found
     * @param parent      The parent classloader, where the API classes can be found
     * @param apiProvider The security manager that sandboxes the classes
     * @param injectors   A collection of byte code injectors to pass all loaded module code through
     */
    public ModuleClassLoader(Name module, URL[] urls, ClassLoader parent, APIProvider apiProvider, Iterable<BytecodeInjector> injectors) {
        super(urls, parent);
        this.moduleId = module;
        this.apiProvider = apiProvider;
        this.bytecodeInjectors = ImmutableList.copyOf(injectors);
        pool = new ClassPool(ClassPool.getDefault());
        for (URL url : urls) {
            try {
                logger.info("Module path: {}", Paths.get(url.toURI()).toString());
                pool.appendClassPath(Paths.get(url.toURI()).toString());
            } catch (NotFoundException | URISyntaxException e) {
                logger.error("Failed to process module url: {}", url);
            }
        }
    }

    public Name getModuleId() {
        return moduleId;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        final Class<?> clazz = super.loadClass(name, resolve);
        ClassLoader parentLoader = AccessController.doPrivileged(new ObtainClassloader(clazz));
        if (parentLoader != this && !(parentLoader instanceof ModuleClassLoader)) {
            if (apiProvider.isAPIClass(clazz)) {
                return clazz;
            } else {
                logger.error("Denied access to class (not available to modules): {}", name);
                return null;
            }
        }
        return clazz;
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<Class<?>>() {
                @Override
                public Class<?> run() throws Exception {
                    CtClass cc = pool.get(name);

                    for (BytecodeInjector injector : bytecodeInjectors) {
                        injector.inject(cc);
                    }
                    byte[] b = cc.toBytecode();
                    return defineClass(name, b, 0, b.length);
                }


            });

        } catch (PrivilegedActionException e) {
            throw new ClassNotFoundException("Failed to find or load class " + name, e.getCause());
        }
    }
}
