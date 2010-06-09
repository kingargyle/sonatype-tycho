package org.codehaus.tycho.osgicompiler.test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.tycho.osgicompiler.AbstractOsgiCompilerMojo;
import org.codehaus.tycho.osgicompiler.copied.CompilationFailureException;
import org.codehaus.tycho.testing.AbstractTychoMojoTestCase;

public class OsgiCompilerTest extends AbstractTychoMojoTestCase {

	protected File storage;
	
	@Override
	protected void setUp() throws Exception {
		super.setUp();
		storage = new File(getBasedir(), "target/storage");
		FileUtils.deleteDirectory(storage);
	}

	private AbstractOsgiCompilerMojo getMojo(List<MavenProject> projects, MavenProject project) throws Exception {
		AbstractOsgiCompilerMojo mojo = (AbstractOsgiCompilerMojo) lookupMojo("compile", project.getFile());
		setVariableValueToObject(mojo, "project", project);
//		setVariableValueToObject(mojo, "storage", storage);
		setVariableValueToObject(mojo, "outputDirectory", new File(project.getBuild().getOutputDirectory()).getAbsoluteFile());
        setVariableValueToObject(mojo, "session", newMavenSession(project, projects));
		
		// tycho-compiler-jdt does not support forked compilation
//		        setVariableValueToObject(mojo, "fork", fork? Boolean.TRUE: Boolean.FALSE);
		return mojo;
	}

    public void testAccessRestrictionCompilationError() throws Exception {
		File basedir = getBasedir("projects/accessrules");
		List<MavenProject> projects = getSortedProjects(basedir, null);

		try {
			for (MavenProject project : projects) {
				if (!"pom".equals(project.getPackaging())) {
					getMojo(projects, project).execute();
				}
			}
			fail("Restricted package access");
		} catch (MojoFailureException e) {
			assertTrue(e.getLongMessage().contains("P001Impl is not accessible"));
		}
	}

	public void testAccessRulesClasspath() throws Exception {
		File basedir = getBasedir("projects/accessrules");
		List<MavenProject> projects = getSortedProjects(basedir, null);

		getMojo(projects, projects.get(1)).execute();
		getMojo(projects, projects.get(2)).execute();
		getMojo(projects, projects.get(3)).execute();

		MavenProject project = projects.get(4);
		AbstractOsgiCompilerMojo mojo = getMojo(projects, project);
		mojo.initializeProjectContext();
        List<String> cp = mojo.getClasspathElements();
		assertEquals(4, cp.size());
		assertEquals(getClasspathElement(project.getBasedir(), "target/classes", ""), cp.get(0));
		assertEquals(getClasspathElement(new File(getBasedir()), "target/projects/accessrules/p001/target/classes", "[+p001/*:?**/*]"), cp.get(1));
		// note that PDE sorts dependencies coming via imported-package by symbolicName_version
		assertEquals(getClasspathElement(new File(getBasedir()), "target/projects/accessrules/p003/target/classes", "[+p003/*:?**/*]"), cp.get(2));
		assertEquals(getClasspathElement(new File(getBasedir()), "target/projects/accessrules/p004/target/classes", "[+p004/*:?**/*]"), cp.get(3));
	}

	public void testClasspath() throws Exception {
		File basedir = getBasedir("projects/classpath");
		List<MavenProject> projects = getSortedProjects(basedir, new File(getBasedir(), "src/test/resources/projects/classpath/platform"));

		MavenProject project;
		List<String> cp;

		// simple project
		project = projects.get(1);
		AbstractOsgiCompilerMojo mojo = getMojo(projects, project);
		mojo.initializeProjectContext();
        cp = mojo.getClasspathElements();
		assertEquals(1, cp.size());
		assertEquals(getClasspathElement(project.getBasedir(), "target/classes", ""), cp.get(0));

		// project with nested lib
		project = projects.get(2);
		mojo = getMojo(projects, project);
        mojo.initializeProjectContext();
		cp = mojo.getClasspathElements();
		assertEquals(2, cp.size());
		assertEquals(getClasspathElement(project.getBasedir(), "target/classes", ""), cp.get(0));
		assertEquals(getClasspathElement(project.getBasedir(), "lib/lib.jar", ""), cp.get(1));

		// project with external dependency with nested jar
		project = projects.get(3);
		mojo = getMojo(projects, project);
        mojo.initializeProjectContext();
		cp = mojo.getClasspathElements();
		assertEquals(3, cp.size());
		assertEquals(getClasspathElement(project.getBasedir(), "target/classes", ""), cp.get(0));
		assertEquals(getClasspathElement(new File(getBasedir()), "src/test/resources/projects/classpath/platform/plugins/p003_0.0.1.jar", "[?**/*]"), cp.get(1));
		assertEquals(getClasspathElement(new File(getBasedir()), "target/local-repo/.cache/tycho/p003_0.0.1.jar/lib/lib.jar", "[?**/*]"), cp.get(2));
	}

	private String getClasspathElement(File base, String path, String accessRules) throws IOException {
		String file = new File(base, path).getCanonicalPath();
		return file + accessRules.replace(":", AbstractOsgiCompilerMojo.RULE_SEPARATOR);
	}

	public void test_multisourceP001_viaMojoConfiguration() throws Exception {
		File basedir = getBasedir("projects/multisource/p001");
		List<MavenProject> projects = getSortedProjects(basedir, null);

		MavenProject project = projects.get(0);
		getMojo(projects, project).execute();
		
		assertTrue(new File(project.getBasedir(), "target/classes/p001/p1/P1.class").canRead());
		assertTrue(new File(project.getBasedir(), "target/classes/p001/p2/P2.class").canRead());
	}

	public void test_multisourceP002_viaBuildProperties() throws Exception {
		File basedir = getBasedir("projects/multisource/p002");
		List<MavenProject> projects = getSortedProjects(basedir, null);

		MavenProject project = projects.get(0);
		getMojo(projects, project).execute();
		
		assertTrue(new File(project.getBasedir(), "target/classes/p002/p1/P1.class").canRead());
		assertTrue(new File(project.getBasedir(), "target/classes/p002/p2/P2.class").canRead());
	}

	public void test_multipleOutputJars() throws Exception {
		File basedir = getBasedir("projects/multijar");
		List<MavenProject> projects = getSortedProjects(basedir, null);

		MavenProject project = projects.get(0);
		getMojo(projects, project).execute();

		assertTrue(new File(project.getBasedir(), "target/classes/src/Src.class").canRead());
		assertTrue(new File(project.getBasedir(), "target/library.jar-classes/src2/Src2.class").canRead());
	}
	
	public void testCopyResources() throws Exception { 
		File basedir = getBasedir("projects/resources/p001");
		List<MavenProject> projects = getSortedProjects(basedir, null);
		MavenProject project = projects.get(0);
		getMojo(projects, project).execute();
		assertTrue(new File(project.getBasedir(), "target/classes/testresources/Test.class").canRead());
		assertTrue(new File(project.getBasedir(), "target/classes/testresources/test.properties").canRead());
	}

	public void testExcludeCopyResources() throws Exception { 
		File basedir = getBasedir("projects/resources/p002");
		List<MavenProject> projects = getSortedProjects(basedir, null);
		MavenProject project = projects.get(0);
		getMojo(projects, project).execute();
		assertTrue(new File(project.getBasedir(), "target/classes/testresources/Test.class").canRead());
		assertFalse(new File(project.getBasedir(), "target/classes/testresources/Test.aj").canRead());
	}
	public void testExecutionEnvironment() throws Exception {
		File basedir = getBasedir("projects/executionEnvironment");
		List<MavenProject> projects = getSortedProjects(basedir, null);
		MavenProject project;
		// project which compiles with source level 1.6 only
		project = projects.get(1);
		getMojo(projects, project).execute();
		assertTrue(new File(project.getBasedir(),
				"target/classes/TestRunnable.class").canRead());
		// project with multiple execution envs.
		// Minimum source and target level must be taken
		project = projects.get(2);
		try {
			getMojo(projects, project).execute();
			fail("compilation failure due to assert keyword expected");
		} catch (CompilationFailureException e) {
			// expected
		}
		// project with both compiler configuration in pom.xml
		// and Bundle-RequiredExecutionEnvironment. Latter should win.
		// build will produce a warning that source/target level was overridden
		// from MANIFEST
		project = projects.get(3);
		getMojo(projects, project).execute();
		assertTrue(new File(project.getBasedir(),
				"target/classes/TestRunnable.class").canRead());
	}
	
}
