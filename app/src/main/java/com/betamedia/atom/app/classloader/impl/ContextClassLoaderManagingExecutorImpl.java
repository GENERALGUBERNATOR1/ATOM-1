package com.betamedia.atom.app.classloader.impl;

import com.betamedia.atom.app.classloader.ContextClassLoaderManagingExecutor;
import com.betamedia.atom.app.classloader.URLClassLoaderFactory;
import com.betamedia.atom.app.storage.TempStorageService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * <h1>ContextClassLoaderManagingExecutorImpl</h1>
 * This class provides context URLClassLoader for execution of given {@link Supplier}.
 * Proper thread safety mechanisms are utilized to support concurrent classloader updates and code execution.
 *
 * @author Maksym Bieliaiev
 * @version 1.0
 * @see java.net.URLClassLoader
 * @see ReentrantReadWriteLock
 * @since 2017-04-13
 */
@Service
public class ContextClassLoaderManagingExecutorImpl implements ContextClassLoaderManagingExecutor {
    private static final Logger logger = LogManager.getLogger(ContextClassLoaderManagingExecutorImpl.class);

    @Autowired
    private TempStorageService storageService;
    @Autowired
    private URLClassLoaderFactory classLoaderFactory;

    private final JarData jarData = new JarData();

    /**
     * <p>
     * This method is used to store or replace JAR binary to be used for context classloader.
     * The method first acquires the JAR path write lock to update JAR path that is used to construct URLClassLoaders.
     * Then, if JAR file write lock is available, it is acquired by execution thread.
     * Old JAR file and JAR path are replaces with new ones and jarData are released.</p>
     * <p>
     * In case of another thread executing code that uses old classloader, JAR file write lock will not be available (see {@link #runWithGlobalJar(List)}).
     * To avoid waiting for potentially expensive operations to complete, new JAR file is stored with autogenerated name and does not overwrite the old JAR file.
     * A separate thread is assigned to delete the old JAR file when the write lock for it becomes available.
     * The JAR path is updated to point to the new JAR file.
     * New JAR file lock is created to control access to the new file.
     * Finally, JAR path write lock is released.</p>
     *
     * @param jar The uploaded JAR binary
     */
    @Override
    public void store(MultipartFile jar) {
        jarData.store(jar, storageService);
    }

    /**
     * <p>
     * This method is used to set JAR path that is used to construct URLClassLoaders.
     * Typically used to set default library to use for context classloader after application deployment.
     * </p>
     *
     * @param jarPath The path to JAR file
     */
    @Override
    public void setJarPath(String jarPath) {
        jarData.setJarPath(jarPath);
    }

    @Override
    public <T> T run(Supplier<T> supplier, Optional<String> jarPath) {
        return jarPath
                .map(path -> runWithProvidedJar(supplier, path))
                .orElseGet(() -> runWithGlobalJar(supplier));
    }

    /**
     * This method executes {@link Supplier} object under default test library classloader.
     *
     * @param supplier Supplier object to execute
     */
    private <T> T runWithGlobalJar(Supplier<T> supplier) {
        try {
            return jarData.run(forClassloader(supplier));
        } catch (MalformedURLException e) {
            logger.error("Failed to retrieve classloader!", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * This method executes {@link Supplier} object, providing temporary context classloader made using uploaded JAR file.
     * This allows users to temporarily override configured JAR library with their own without interfering with other threads.
     *
     * @param supplier Supplier object to execute
     * @param jarPath   Path to the uploaded JAR binary
     */
    private <T> T runWithProvidedJar(Supplier<T> supplier, String jarPath) {
        try {
            URL jarURL = classLoaderFactory.getURL(jarPath);
            return forClassloader(supplier)
                    .apply(parent -> classLoaderFactory.get(jarURL, parent));
        } catch (MalformedURLException e) {
            logger.error("Failed to retrieve classloader!", e);
            throw new RuntimeException(e);
        }
    }

    private static <T> Function<Function<ClassLoader, ClassLoader>, T> forClassloader(Supplier<T> resultSupplier) {
        return clSupplier -> run(resultSupplier, clSupplier);
    }

    private static <T> T run(Supplier<T> s,
                             Function<ClassLoader, ClassLoader> clSupplier) {
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(clSupplier.apply(parent));
            return s.get();
        } finally {
            Thread.currentThread().setContextClassLoader(parent);
        }
    }

    @Override
    public String getJarVersion() {
        return jarData.getJarVersion();
    }

    private class JarData {
        private final ReadWriteLock pathLock = new ReentrantReadWriteLock(true);
        private volatile ReadWriteLock jarLock = new ReentrantReadWriteLock(true);
        private volatile String jarPath;
        private volatile String jarVersion;

        private void setJarVersion() {
            pathLock.readLock().lock();
            try (JarFile jar = new JarFile(jarPath)) {
                Manifest manifest = jar.getManifest();
                Attributes attributes = manifest.getMainAttributes();
                jarVersion = attributes.getValue("Implementation-Version");
            } catch (Exception ex) {
                logger.warn("Couldn't get testslibrary version", ex.getMessage());
            } finally {
                pathLock.readLock().unlock();
            }
        }

        private String getJarVersion() {
            pathLock.readLock().lock();
            try {
                return jarVersion;
            } finally {
                pathLock.readLock().unlock();
            }
        }

        private void setJarPath(String jarPath) {
            pathLock.writeLock().lock();
            try {
                this.jarPath = jarPath;
                setJarVersion();
            } finally {
                pathLock.writeLock().unlock();
            }
        }

        private void store(MultipartFile jar, TempStorageService storageService) {
            pathLock.writeLock().lock();
            try {
                if (jarLock.writeLock().tryLock()) {
                    try {
                        setJarPath(storageService.storeToTemp(jar, "testslibrary-" + UUID.randomUUID()));
                    } finally {
                        jarLock.writeLock().unlock();
                    }
                } else {
                    setJarPath(storageService.storeToTemp(jar, "testslibrary-" + UUID.randomUUID()));
                    this.jarLock = new ReentrantReadWriteLock(true);
                }
                setJarVersion();
            } finally {
                pathLock.writeLock().unlock();
            }
        }

        /**
         * This method orchestrates classloader-dependent code execution with thread security mechanisms.
         *
         * The method first acquires read lock on JAR path - execution will block until it's available if JAR file is
         * currently being updated. Reference to current JAR file lock is stored in local variable, as it is expected to
         * change once classloader for current JAR path is initialized and JAR path read lock is released. <br/>
         * After acquiring JAR file read lock, the URL object for the given path and file is generated by
         * {@link URLClassLoaderFactory}. Then, JAR path read lock is released, because we no longer need the path
         * variable itself. JAR file read lock is held for entire duration of test execution.
         * This allows other threads to update JAR file without waiting for other tests that use old JAR file to complete.<br/>
         *
         *  @param forClassloader an object that accepts a strategy for creating new classloader (either based on global
         *                        or temporary test library) and executes test code under that classloader
         * */
        private <T> T run(Function<Function<ClassLoader, ClassLoader>, T> forClassloader) throws MalformedURLException {
            URL jarURL;
            pathLock.readLock().lock();
            ReadWriteLock oldJarLock = this.jarLock;
            oldJarLock.readLock().lock();
            try {
                try {
                    jarURL = classLoaderFactory.getURL(jarPath);
                } finally {
                    pathLock.readLock().unlock();
                }
                return forClassloader.apply(parent -> classLoaderFactory.get(jarURL, parent));
            } finally {
                oldJarLock.readLock().unlock();
            }
        }

    }
}
