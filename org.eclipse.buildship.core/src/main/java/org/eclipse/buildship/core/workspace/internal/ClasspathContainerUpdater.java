/*
 * Copyright (c) 2015 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Etienne Studer & Donát Csikós (Gradle Inc.) - initial API and implementation and initial documentation
 */

package org.eclipse.buildship.core.workspace.internal;

import java.util.List;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import com.gradleware.tooling.toolingmodel.OmniEclipseProject;
import com.gradleware.tooling.toolingmodel.OmniEclipseProjectDependency;
import com.gradleware.tooling.toolingmodel.OmniExternalDependency;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.buildship.core.gradle.Specs;
import org.eclipse.buildship.core.workspace.internal.GradleClasspathContainerInitializer.GradleClasspathContainer;

/**
 * Updates the classpath container of the target project.
 * <p/>
 * The execute the update call the static {@link #update(IJavaProject, OmniEclipseProject, IPath)}
 * method. The method executes synchronously and unprotected, without thread synchronization or job
 * scheduling.
 * <p/>
 * The update logic transforms all project and external dependencies to Eclipse classpath container
 * entries and and uses them as the content of a fresh classpath container. At the end of the
 * execution the new container is assigned to the project.
 * <p/>
 * If an invalid external dependency is received (anything else, than a {@code .jar} file) then that
 * entry is omitted from the classpath container. Due to performance reasons only the file extension
 * is checked.
 */
public final class ClasspathContainerUpdater {

    private final IJavaProject eclipseProject;
    private final OmniEclipseProject gradleProject;
    private final IPath classpathContainerPath;

    private ClasspathContainerUpdater(IJavaProject eclipseProject, OmniEclipseProject gradleProject, IPath classpathContainerPath) {
        this.eclipseProject = Preconditions.checkNotNull(eclipseProject);
        this.gradleProject = Preconditions.checkNotNull(gradleProject);
        this.classpathContainerPath = Preconditions.checkNotNull(classpathContainerPath);
    }

    private void updateClasspathContainer() throws JavaModelException {
        ImmutableList<IClasspathEntry> containerEntries = collectClasspathContainerEntries();
        setClasspathContainer(containerEntries);
    }

    private ImmutableList<IClasspathEntry> collectClasspathContainerEntries() {
        // project dependencies
        List<IClasspathEntry> projectDependencies = FluentIterable.from(this.gradleProject.getProjectDependencies())
                .transform(new Function<OmniEclipseProjectDependency, IClasspathEntry>() {

                    @Override
                    public IClasspathEntry apply(OmniEclipseProjectDependency dependency) {
                        OmniEclipseProject dependentProject = ClasspathContainerUpdater.this.gradleProject.getRoot()
                                .tryFind(Specs.eclipseProjectMatchesProjectPath(dependency.getTargetProjectPath())).get();
                        IPath path = new Path("/" + dependentProject.getName());
                        return JavaCore.newProjectEntry(path, dependency.isExported());
                    }
                }).toList();

        // external dependencies
        List<IClasspathEntry> externalDependencies = FluentIterable.from(this.gradleProject.getExternalDependencies()).filter(new Predicate<OmniExternalDependency>() {

            @Override
            public boolean apply(OmniExternalDependency dependency) {
                // Eclipse only accepts archives as external dependencies (but not, for example, a
                // DLL)
                String name = dependency.getFile().getName();
                return name.endsWith(".jar") || name.endsWith(".zip");
            }
        }).transform(new Function<OmniExternalDependency, IClasspathEntry>() {

            @Override
            public IClasspathEntry apply(OmniExternalDependency dependency) {
                IPath jar = org.eclipse.core.runtime.Path.fromOSString(dependency.getFile().getAbsolutePath());
                IPath sourceJar = dependency.getSource() != null ? org.eclipse.core.runtime.Path.fromOSString(dependency.getSource().getAbsolutePath()) : null;
                return JavaCore.newLibraryEntry(jar, sourceJar, null, dependency.isExported());
            }
        }).toList();

        // return all dependencies as a joined list
        return ImmutableList.<IClasspathEntry> builder().addAll(projectDependencies).addAll(externalDependencies).build();
    }

    private void setClasspathContainer(List<IClasspathEntry> classpathEntries) throws JavaModelException {
        IClasspathContainer classpathContainer = new GradleClasspathContainer("Project and External Dependencies", this.classpathContainerPath, classpathEntries);
        JavaCore.setClasspathContainer(this.classpathContainerPath, new IJavaProject[] { this.eclipseProject }, new IClasspathContainer[] {
                classpathContainer }, new NullProgressMonitor());
    }

    /**
     * Updates the classpath container of the target project.
     *
     * @param eclipseProject the target project to update the classpath container on
     * @param gradleProject the Gradle model to load the dependencies from
     * @param classpathContainerPath the container path where to assign the classpath container
     *            entry
     * @throws JavaModelException if the container assignment fails
     */
    public static void update(IJavaProject eclipseProject, OmniEclipseProject gradleProject, IPath classpathContainerPath) throws JavaModelException {
        new ClasspathContainerUpdater(eclipseProject, gradleProject, classpathContainerPath).updateClasspathContainer();
    }
}