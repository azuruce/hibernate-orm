/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.orm.tooling.maven;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import org.hibernate.bytecode.enhance.spi.DefaultEnhancementContext;
import org.hibernate.bytecode.enhance.spi.EnhancementContext;
import org.hibernate.bytecode.enhance.spi.Enhancer;
import org.hibernate.bytecode.enhance.spi.UnloadedClass;
import org.hibernate.bytecode.enhance.spi.UnloadedField;
import org.hibernate.cfg.Environment;

import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * This plugin will enhance Entity objects.
 *
 * @author Jeremy Whiting
 * @author Luis Barreiro
 */
@Mojo(name = "enhance", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
@Execute(goal = "enhance", phase = LifecyclePhase.COMPILE)
public class MavenEnhancePlugin extends AbstractMojo {

	/**
	 * The contexts to use during enhancement.
	 */
	private List<File> sourceSet = new ArrayList<File>();

	@Component
	private BuildContext buildContext;

	@Parameter(property = "dir", defaultValue = "${project.build.outputDirectory}")
	private String dir;

	@Parameter(property = "failOnError", defaultValue = "true")
	private boolean failOnError = true;

	@Parameter(property = "enableLazyInitialization", defaultValue = "false")
	private boolean enableLazyInitialization;

	@Parameter(property = "enableDirtyTracking", defaultValue = "false")
	private boolean enableDirtyTracking;

	@Parameter(property = "enableAssociationManagement", defaultValue = "false")
	private boolean enableAssociationManagement;

	@Parameter(property = "enableExtendedEnhancement", defaultValue = "false")
	private boolean enableExtendedEnhancement;

	private boolean shouldApply() {
		return enableLazyInitialization || enableDirtyTracking || enableAssociationManagement || enableExtendedEnhancement;
	}

	public void execute() throws MojoExecutionException, MojoFailureException {
		if ( !shouldApply() ) {
			getLog().warn( "Skipping Hibernate bytecode enhancement plugin execution since no feature is enabled" );
			return;
		}

		// Perform a depth first search for sourceSet
		File root = new File( this.dir );
		if ( !root.exists() ) {
			getLog().info( "Skipping Hibernate enhancement plugin execution since there is no classes dir " + dir );
			return;
		}
		walkDir( root );
		if ( sourceSet.isEmpty() ) {
			getLog().info( "Skipping Hibernate enhancement plugin execution since there are no classes to enhance on " + dir );
			return;
		}

		getLog().info( "Starting Hibernate enhancement for classes on " + dir );
		final ClassLoader classLoader = toClassLoader( Collections.singletonList( root ) );

		EnhancementContext enhancementContext = new DefaultEnhancementContext() {
			@Override
			public ClassLoader getLoadingClassLoader() {
				return classLoader;
			}

			@Override
			public boolean doBiDirectionalAssociationManagement(UnloadedField field) {
				return enableAssociationManagement;
			}

			@Override
			public boolean doDirtyCheckingInline(UnloadedClass classDescriptor) {
				return enableDirtyTracking;
			}

			@Override
			public boolean hasLazyLoadableAttributes(UnloadedClass classDescriptor) {
				return enableLazyInitialization;
			}

			@Override
			public boolean isLazyLoadable(UnloadedField field) {
				return enableLazyInitialization;
			}

			@Override
			public boolean doExtendedEnhancement(UnloadedClass classDescriptor) {
				return enableExtendedEnhancement;
			}
		};

		if ( enableExtendedEnhancement ) {
			getLog().warn( "Extended enhancement is enabled. Classes other than entities may be modified. You should consider access the entities using getter/setter methods and disable this property. Use at your own risk." );
		}

		final Enhancer enhancer = Environment.getBytecodeProvider().getEnhancer( enhancementContext );

		for ( File file : sourceSet ) {

			final byte[] enhancedBytecode = doEnhancement( file, enhancer );

			if ( enhancedBytecode == null ) {
				continue;
			}

			writeOutEnhancedClass( enhancedBytecode, file );

			getLog().info( "Successfully enhanced class [" + file + "]" );
		}
	}

	private ClassLoader toClassLoader(List<File> runtimeClasspath) throws MojoExecutionException {
		List<URL> urls = new ArrayList<URL>();
		for ( File file : runtimeClasspath ) {
			try {
				urls.add( file.toURI().toURL() );
				getLog().debug( "Adding classpath entry for classes root " + file.getAbsolutePath() );
			}
			catch (MalformedURLException e) {
				String msg = "Unable to resolve classpath entry to URL: " + file.getAbsolutePath();
				if ( failOnError ) {
					throw new MojoExecutionException( msg, e );
				}
				getLog().warn( msg );
			}
		}

		// HHH-10145 Add dependencies to classpath as well - all but the ones used for testing purposes
		MavenProject project = ( (MavenProject) getPluginContext().get( "project" ) );
		Set<Artifact> artifacts = project.getArtifacts();
		if ( artifacts != null) {
			for ( Artifact a : artifacts ) {
				if ( !Artifact.SCOPE_TEST.equals( a.getScope() ) ) {
					try {
						urls.add( a.getFile().toURI().toURL() );
						getLog().debug( "Adding classpath entry for dependency " + a.getId() );
					}
					catch (MalformedURLException e) {
						String msg = "Unable to resolve URL for dependency " + a.getId() + " at " + a.getFile().getAbsolutePath();
						if ( failOnError ) {
							throw new MojoExecutionException( msg, e );
						}
						getLog().warn( msg );
					}
				}
			}
		}

		return new URLClassLoader( urls.toArray( new URL[urls.size()] ), Enhancer.class.getClassLoader() );
	}

	private byte[] doEnhancement(File javaClassFile, Enhancer enhancer) throws MojoExecutionException {
		try {
			return enhancer.enhance(javaClassFile);
		}
		catch (Exception e) {
			String msg = "Unable to enhance class: " + javaClassFile.getName();
			if ( failOnError ) {
				throw new MojoExecutionException( msg, e );
			}
			buildContext.addMessage( javaClassFile, 0, 0, msg, BuildContext.SEVERITY_WARNING, e );
			return null;
		}
	}

	/**
	 * Expects a directory.
	 */
	private void walkDir(File dir) {
		walkDir(
				dir,
				new FileFilter() {
					@Override
					public boolean accept(File pathname) {
						return ( pathname.isFile() && pathname.getName().endsWith( ".class" ) );
					}
				},
				new FileFilter() {
					@Override
					public boolean accept(File pathname) {
						return ( pathname.isDirectory() );
					}
				}
		);
	}

	private void walkDir(File dir, FileFilter classesFilter, FileFilter dirFilter) {
		File[] dirs = dir.listFiles( dirFilter );
		for ( File dir1 : dirs ) {
			walkDir( dir1, classesFilter, dirFilter );
		}
		File[] files = dir.listFiles( classesFilter );
		Collections.addAll( this.sourceSet, files );
	}

	private void writeOutEnhancedClass(byte[] enhancedBytecode, File file) throws MojoExecutionException {
		try {
			if ( file.delete() ) {
				if ( !file.createNewFile() ) {
					buildContext.addMessage( file, 0, 0, "Unable to recreate class file", BuildContext.SEVERITY_ERROR, null );
				}
			}
			else {
				buildContext.addMessage( file, 0, 0, "Unable to delete class file", BuildContext.SEVERITY_ERROR, null );
			}
		}
		catch (IOException e) {
			buildContext.addMessage( file, 0, 0, "Problem preparing class file for writing out enhancements", BuildContext.SEVERITY_WARNING, e );
		}

		OutputStream outputStream = null;
		try {
			outputStream = buildContext.newFileOutputStream( file );
			outputStream.write( enhancedBytecode );
			outputStream.flush();
		}
		catch (IOException e) {
			String msg = String.format( "Error writing to enhanced class [%s] to file [%s]", file.getName(), file.getAbsolutePath() );
			if ( failOnError ) {
				throw new MojoExecutionException( msg, e );
			}
			buildContext.addMessage( file, 0, 0, msg, BuildContext.SEVERITY_WARNING, e );
		}
		finally {
			try {
				if( outputStream != null ) {
					outputStream.close();
				}
			}
			catch (IOException ignore) {
			}
		}
	}
}
