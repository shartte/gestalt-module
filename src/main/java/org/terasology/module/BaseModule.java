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

package org.terasology.module;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.naming.Name;
import org.terasology.naming.Version;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;

/**
 * Common base abstract class for modules.
 *
 * @author Immortius
 */
public abstract class BaseModule implements Module {
    private static final String REFLECTIONS_CACHE_FILE = "reflections.cache";
    private static final Logger logger = LoggerFactory.getLogger(BaseModule.class);

    protected ImmutableList<Path> paths;
    protected ModuleMetadata metadata;

    private Reflections reflectionsFragment;

    /**
     * @param paths    The paths composing the module
     * @param metadata The metadata describing the module
     */
    public BaseModule(Collection<Path> paths, ModuleMetadata metadata) {
        this.paths = ImmutableList.copyOf(paths);
        this.metadata = metadata;
    }

    @Override
    public ImmutableList<Path> getLocations() {
        return paths;
    }

    @Override
    public Name getId() {
        return metadata.getId();
    }

    @Override
    public Version getVersion() {
        return metadata.getVersion();
    }

    @Override
    public ModuleMetadata getMetadata() {
        return metadata;
    }

    @Override
    public Reflections getReflectionsFragment() {
        Preconditions.checkState(isCodeModule(), "Cannot get reflections fragment for non-code module");
        if (reflectionsFragment == null) {
            for (Path path : paths) {
                if (Files.isDirectory(path) && Files.isRegularFile(path.resolve(REFLECTIONS_CACHE_FILE))) {
                    collectCacheFromPath(path);
                } else if (Files.isRegularFile(path)) {
                    collectCacheFromArchive(path);
                }
            }
            if (reflectionsFragment == null) {
                scanForReflections();
            }
        }
        return reflectionsFragment;
    }

    private void scanForReflections() {
        reflectionsFragment = new ConfigurationBuilder()
                .addUrls(getClasspaths())
                .setScanners(new TypeAnnotationsScanner(), new SubTypesScanner())
                .addClassLoader(ClasspathHelper.staticClassLoader())
                .build();
    }

    private void collectCacheFromArchive(Path path) {
        try (FileSystem archive = FileSystems.newFileSystem(path, null)) {
            Path cachePath = archive.getPath(REFLECTIONS_CACHE_FILE);
            if (Files.isRegularFile(cachePath)) {
                if (reflectionsFragment == null) {
                    reflectionsFragment = new ConfigurationBuilder().addClassLoader(ClasspathHelper.staticClassLoader()).build();
                }
                try (InputStream stream = new BufferedInputStream(Files.newInputStream(cachePath))) {
                    reflectionsFragment.collect(stream);
                }
            }
        } catch (IOException e) {
            logger.error("Failure attempting to read reflections cache from {}", this, e);
        }
    }

    private void collectCacheFromPath(Path path) {
        if (reflectionsFragment == null) {
            reflectionsFragment = new ConfigurationBuilder().addClassLoader(ClasspathHelper.staticClassLoader()).build();
        }
        reflectionsFragment.collect(path.resolve(REFLECTIONS_CACHE_FILE).toFile());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof BaseModule) {
            BaseModule other = (BaseModule) obj;
            return Objects.equals(other.getId(), getId()) && Objects.equals(other.getVersion(), getVersion());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), getVersion());
    }

    @Override
    public String toString() {
        return metadata.getId() + "-" + metadata.getVersion();
    }
}
