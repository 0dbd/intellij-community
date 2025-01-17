/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.testFramework;

import com.intellij.codeInsight.daemon.impl.EditorTracker;
import com.intellij.ide.highlighter.ModuleFileType;
import com.intellij.ide.highlighter.ProjectFileType;
import com.intellij.ide.startup.impl.StartupManagerImpl;
import com.intellij.idea.IdeaLogger;
import com.intellij.idea.IdeaTestApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.impl.UndoManagerImpl;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.impl.FileTypeManagerImpl;
import com.intellij.openapi.module.EmptyModuleType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.module.impl.ModuleManagerImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.project.impl.ProjectManagerImpl;
import com.intellij.openapi.project.impl.TooManyProjectLeakedException;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.impl.local.LocalFileSystemImpl;
import com.intellij.openapi.vfs.newvfs.impl.VirtualDirectoryImpl;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFSImpl;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.codeStyle.CodeStyleSchemes;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.impl.DocumentCommitThread;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageManagerImpl;
import com.intellij.util.PlatformUtils;
import com.intellij.util.indexing.IndexableSetContributor;
import com.intellij.util.indexing.IndexedRootsProvider;
import com.intellij.util.ui.UIUtil;
import junit.framework.TestCase;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * @author yole
 */
public abstract class PlatformTestCase extends UsefulTestCase implements DataProvider {
  public static final String TEST_DIR_PREFIX = "idea_test_";

  protected static IdeaTestApplication ourApplication;
  protected ProjectManagerEx myProjectManager;
  protected Project myProject;
  protected Module myModule;
  protected static final Collection<File> myFilesToDelete = new HashSet<File>();
  protected boolean myAssertionsInTestDetected;
  protected static final Logger LOG = Logger.getInstance("#com.intellij.testFramework.PlatformTestCase");
  public static Thread ourTestThread;
  private static TestCase ourTestCase = null;
  public static final long DEFAULT_TEST_TIME = 300L;
  public static long ourTestTime = DEFAULT_TEST_TIME;
  private EditorListenerTracker myEditorListenerTracker;
  private ThreadTracker myThreadTracker;

  protected static boolean ourPlatformPrefixInitialized;
  private static Set<VirtualFile> ourEternallyLivingFilesCache;

  static {
    Logger.setFactory(TestLoggerFactory.class);
  }

  protected static long getTimeRequired() {
    return DEFAULT_TEST_TIME;
  }

  /**
   * If a temp directory is reused from some previous test run, there might be cached children in its VFS.
   * Ensure they're removed
   */
  public static void synchronizeTempDirVfs(VirtualFile tempDir) {
    tempDir.getChildren();
    tempDir.refresh(false, true);
  }

  @Nullable
  protected String getApplicationConfigDirPath() throws Exception {
    return null;
  }

  protected void initApplication() throws Exception {
    boolean firstTime = ourApplication == null;
    autodetectPlatformPrefix();
    ourApplication = IdeaTestApplication.getInstance(getApplicationConfigDirPath());
    ourApplication.setDataProvider(this);

    if (firstTime) {
      cleanPersistedVFSContent();
    }
  }

  private static final String[] PREFIX_CANDIDATES = {
    "AppCode", "CLion", "CidrCommon", 
    "Python", "PyCharmCore", "Ruby", "UltimateLangXml", "Idea", "PlatformLangXml" };

  /**
   * @deprecated calling this method is no longer necessary
   */
  public static void autodetectPlatformPrefix() {
    doAutodetectPlatformPrefix();
  }

  public static void doAutodetectPlatformPrefix() {
    if (ourPlatformPrefixInitialized) {
      return;
    }
    URL resource = PlatformTestCase.class.getClassLoader().getResource("idea/ApplicationInfo.xml");
    if (resource == null) {
      for (String candidate : PREFIX_CANDIDATES) {
        resource = PlatformTestCase.class.getClassLoader().getResource("META-INF/" + candidate + "Plugin.xml");
        if (resource != null) {
          setPlatformPrefix(candidate);
          break;
        }
      }
    }
  }

  private static void cleanPersistedVFSContent() {
    ((PersistentFSImpl)PersistentFS.getInstance()).cleanPersistedContents();
  }

  @Override
  protected CodeStyleSettings getCurrentCodeStyleSettings() {
    if (CodeStyleSchemes.getInstance().getCurrentScheme() == null) return new CodeStyleSettings();
    return CodeStyleSettingsManager.getSettings(getProject());
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    if (ourTestCase != null) {
      String message = "Previous test " + ourTestCase + " hasn't called tearDown(). Probably overridden without super call.";
      ourTestCase = null;
      fail(message);
    }
    IdeaLogger.ourErrorsOccurred = null;

    LOG.info(getClass().getName() + ".setUp()");

    initApplication();

    myEditorListenerTracker = new EditorListenerTracker();
    myThreadTracker = new ThreadTracker();

    setUpProject();

    storeSettings();
    ourTestCase = this;
    if (myProject != null) {
      ProjectManagerEx.getInstanceEx().openTestProject(myProject);
      CodeStyleSettingsManager.getInstance(myProject).setTemporarySettings(new CodeStyleSettings());
      InjectedLanguageManagerImpl.pushInjectors(getProject());
    }

    DocumentCommitThread.getInstance().clearQueue();
    UIUtil.dispatchAllInvocationEvents();
  }

  public Project getProject() {
    return myProject;
  }

  public final PsiManager getPsiManager() {
    return PsiManager.getInstance(myProject);
  }

  public Module getModule() {
    return myModule;
  }

  protected void setUpProject() throws Exception {
    myProjectManager = ProjectManagerEx.getInstanceEx();
    assertNotNull("Cannot instantiate ProjectManager component", myProjectManager);

    File projectFile = getIprFile();

    myProject = doCreateProject(projectFile);
    myProjectManager.openTestProject(myProject);
    myProject.getComponent(EditorTracker.class).projectOpened();
    LocalFileSystem.getInstance().refreshIoFiles(myFilesToDelete);

    setUpModule();

    setUpJdk();

    LightPlatformTestCase.clearUncommittedDocuments(getProject());

    runStartupActivities();
    ((FileTypeManagerImpl)FileTypeManager.getInstance()).drainReDetectQueue();
  }

  protected Project doCreateProject(File projectFile) throws Exception {
    return createProject(projectFile, getClass().getName() + "." + getName());
  }

  @NotNull
  public static Project createProject(File projectFile, String creationPlace) {
    try {
      Project project =
        ProjectManagerEx.getInstanceEx().newProject(FileUtil.getNameWithoutExtension(projectFile), projectFile.getPath(), false, false);
      assert project != null;

      project.putUserData(CREATION_PLACE, creationPlace);
      return project;
    }
    catch (TooManyProjectLeakedException e) {
      StringBuilder leakers = new StringBuilder();
      leakers.append("Too many projects leaked: \n");
      for (Project project : e.getLeakedProjects()) {
        String presentableString = getCreationPlace(project);
        leakers.append(presentableString);
        leakers.append("\n");
      }

      fail(leakers.toString());
      return null;
    }
  }

  @NotNull
  public static String getCreationPlace(@NotNull Project project) {
    String place = project.getUserData(CREATION_PLACE);
    Object base;
    try {
      base = project.isDisposed() ? "" : project.getBaseDir();
    }
    catch (Exception e) {
      base = " (" + e + " while getting base dir)";
    }
    return project + (place != null ? place : "") + base;
  }

  protected void runStartupActivities() {
    final StartupManagerImpl startupManager = (StartupManagerImpl)StartupManager.getInstance(myProject);
    startupManager.runStartupActivities();
    startupManager.startCacheUpdate();
    startupManager.runPostStartupActivities();
  }

  protected File getIprFile() throws IOException {
    File tempFile = FileUtil.createTempFile(getName() + "_", ProjectFileType.DOT_DEFAULT_EXTENSION);
    myFilesToDelete.add(tempFile);
    return tempFile;
  }

  protected void setUpModule() {
    new WriteCommandAction.Simple(getProject()) {
      @Override
      protected void run() throws Throwable {
        myModule = createMainModule();
      }
    }.execute().throwException();
  }

  protected Module createMainModule() throws IOException {
    return createModule(myProject.getName());
  }

  protected Module createModule(@NonNls final String moduleName) {
    return doCreateRealModule(moduleName);
  }

  protected Module doCreateRealModule(final String moduleName) {
    return doCreateRealModuleIn(moduleName, myProject, getModuleType());
  }

  protected static Module doCreateRealModuleIn(String moduleName, final Project project, final ModuleType moduleType) {
    final VirtualFile baseDir = project.getBaseDir();
    assertNotNull(baseDir);
    final File moduleFile = new File(FileUtil.toSystemDependentName(baseDir.getPath()), moduleName + ModuleFileType.DOT_DEFAULT_EXTENSION);
    FileUtil.createIfDoesntExist(moduleFile);
    myFilesToDelete.add(moduleFile);
    return new WriteAction<Module>() {
      @Override
      protected void run(@NotNull Result<Module> result) throws Throwable {
        VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(moduleFile);
        assertNotNull(virtualFile);
        Module module = ModuleManager.getInstance(project).newModule(virtualFile.getPath(), moduleType.getId());
        module.getModuleFile();
        result.setResult(module);
      }
    }.execute().getResultObject();
  }

  protected ModuleType getModuleType() {
    return EmptyModuleType.getInstance();
  }

  public static void cleanupApplicationCaches(Project project) {
    if (project != null && !project.isDisposed()) {
      UndoManagerImpl globalInstance = (UndoManagerImpl)UndoManager.getGlobalInstance();
      if (globalInstance != null) {
        globalInstance.dropHistoryInTests();
      }
      ((UndoManagerImpl)UndoManager.getInstance(project)).dropHistoryInTests();

      ((PsiManagerEx)PsiManager.getInstance(project)).getFileManager().cleanupForNextTest();
    }

    ProjectManagerImpl projectManager = (ProjectManagerImpl)ProjectManager.getInstance();
    if (projectManager.isDefaultProjectInitialized()) {
      Project defaultProject = projectManager.getDefaultProject();
      ((PsiManagerEx)PsiManager.getInstance(defaultProject)).getFileManager().cleanupForNextTest();
    }

    LocalFileSystemImpl localFileSystem = (LocalFileSystemImpl)LocalFileSystem.getInstance();
    if (localFileSystem != null) {
      localFileSystem.cleanupForNextTest();
    }

  }

  private static Set<VirtualFile> eternallyLivingFiles() {
    if (ourEternallyLivingFilesCache != null) {
      return ourEternallyLivingFilesCache;
    }

    Set<VirtualFile> survivors = new HashSet<VirtualFile>();

    for (IndexedRootsProvider provider : IndexedRootsProvider.EP_NAME.getExtensions()) {
      for (VirtualFile file : IndexableSetContributor.getRootsToIndex(provider)) {
        registerSurvivor(survivors, file);
      }
    }

    ourEternallyLivingFilesCache = survivors;
    return survivors;
  }

  public static void addSurvivingFiles(@NotNull Collection<VirtualFile> files) {
    for (VirtualFile each : files) {
      registerSurvivor(eternallyLivingFiles(), each);
    }
  }

  private static void registerSurvivor(Set<VirtualFile> survivors, VirtualFile file) {
    addSubTree(file, survivors);
    while (file != null && survivors.add(file)) {
      file = file.getParent();
    }
  }

  private static void addSubTree(VirtualFile root, Set<VirtualFile> to) {
    if (root instanceof VirtualDirectoryImpl) {
      for (VirtualFile child : ((VirtualDirectoryImpl)root).getCachedChildren()) {
        if (child instanceof VirtualDirectoryImpl) {
          to.add(child);
          addSubTree(child, to);
        }
      }
    }
  }

  @Override
  protected void tearDown() throws Exception {
    CompositeException result = new CompositeException();
    if (myProject != null) {
      try {
        LightPlatformTestCase.doTearDown(getProject(), ourApplication, false);
      }
      catch (Throwable e) {
        result.add(e);
      }
    }

    try {
      CompositeException damage = checkForSettingsDamage();
      result.add(damage);
    }
    catch (Throwable e) {
      result.add(e);
    }
    try {
      Project project = getProject();
      disposeProject(result);

      if (project != null) {
        try {
          InjectedLanguageManagerImpl.checkInjectorsAreDisposed(project);
        }
        catch (AssertionError e) {
          result.add(e);
        }
      }
      try {
        for (final File fileToDelete : myFilesToDelete) {
          delete(fileToDelete);
        }
        LocalFileSystem.getInstance().refreshIoFiles(myFilesToDelete);
      }
      catch (Throwable e) {
        result.add(e);
      }

      if (!myAssertionsInTestDetected) {
        if (IdeaLogger.ourErrorsOccurred != null) {
          result.add(IdeaLogger.ourErrorsOccurred);
        }
      }

      try {
        super.tearDown();
      }
      catch (Throwable e) {
        result.add(e);
      }

      try {
        if (myEditorListenerTracker != null) {
          myEditorListenerTracker.checkListenersLeak();
        }
      }
      catch (AssertionError error) {
        result.add(error);
      }
      try {
        if (myThreadTracker != null) {
          myThreadTracker.checkLeak();
        }
      }
      catch (AssertionError error) {
        result.add(error);
      }
      try {
        LightPlatformTestCase.checkEditorsReleased();
      }
      catch (Throwable error) {
        result.add(error);
      }
    }
    finally {
      myProjectManager = null;
      myProject = null;
      myModule = null;
      myFilesToDelete.clear();
      myEditorListenerTracker = null;
      myThreadTracker = null;
      ourTestCase = null;
    }
    if (!result.isEmpty()) throw result;
  }

  private void disposeProject(@NotNull CompositeException result) /* throws nothing */ {
    try {
      DocumentCommitThread.getInstance().clearQueue();
      // sometimes SwingUtilities maybe confused about EDT at this point
      if (SwingUtilities.isEventDispatchThread()) {
        UIUtil.dispatchAllInvocationEvents();
      }
    }
    catch (Exception e) {
      result.add(e);
    }
    try {
      if (myProject != null) {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          @Override
          public void run() {
            Disposer.dispose(myProject);
            ProjectManagerEx projectManager = ProjectManagerEx.getInstanceEx();
            if (projectManager instanceof ProjectManagerImpl) {
              Collection<Project> projectsStillOpen = projectManager.closeTestProject(myProject);
              if (!projectsStillOpen.isEmpty()) {
                Project project = projectsStillOpen.iterator().next();
                String message = "Test project is not disposed: " + project + ";\n created in: " + getCreationPlace(project);
                try {
                  projectManager.closeTestProject(project);
                  Disposer.dispose(project);
                }
                catch (Exception e) {
                  // ignore, we already have somthing to throw
                }
                throw new AssertionError(message);
              }
            }
          }
        });
      }
    }
    catch (Exception e) {
      result.add(e);
    }
    finally {
      if (myProject != null) {
        try {
          PsiDocumentManager documentManager = myProject.getComponent(PsiDocumentManager.class, null);
          if (documentManager != null) {
            EditorFactory.getInstance().getEventMulticaster().removeDocumentListener((DocumentListener)documentManager);
          }
        }
        catch (Exception ignored) {

        }
        myProject = null;
      }
    }
  }

  protected void resetAllFields() {
    resetClassFields(getClass());
  }

  @Override
  protected final <T extends Disposable> T disposeOnTearDown(T disposable) {
    Disposer.register(myProject, disposable);
    return disposable;
  }

  private void resetClassFields(final Class<?> aClass) {
    try {
      clearDeclaredFields(this, aClass);
    }
    catch (IllegalAccessException e) {
      LOG.error(e);
    }

    if (aClass == PlatformTestCase.class) return;
    resetClassFields(aClass.getSuperclass());
  }

  private String getFullName() {
    return getClass().getName() + "." + getName();
  }

  private void delete(File file) {
    boolean b = FileUtil.delete(file);
    if (!b && file.exists() && !myAssertionsInTestDetected) {
      fail("Can't delete " + file.getAbsolutePath() + " in " + getFullName());
    }
  }

  protected void simulateProjectOpen() {
    ModuleManagerImpl mm = (ModuleManagerImpl)ModuleManager.getInstance(myProject);
    StartupManagerImpl sm = (StartupManagerImpl)StartupManager.getInstance(myProject);

    mm.projectOpened();
    setUpJdk();
    sm.runStartupActivities();
    sm.startCacheUpdate();
    // extra init for libraries
    sm.runPostStartupActivities();
  }

  protected void setUpJdk() {
    //final ProjectJdkEx jdk = ProjectJdkUtil.getDefaultJdk("java 1.4");
    final Sdk jdk = getTestProjectJdk();
//    ProjectJdkImpl jdk = ProjectJdkTable.getInstance().addJdk(defaultJdk);
    Module[] modules = ModuleManager.getInstance(myProject).getModules();
    for (Module module : modules) {
      ModuleRootModificationUtil.setModuleSdk(module, jdk);
    }
  }

  @Nullable
  protected Sdk getTestProjectJdk() {
    return null;
  }

  @Override
  public void runBare() throws Throwable {
    if (!shouldRunTest()) return;

    replaceIdeEventQueueSafely();
    try {
      runBareImpl();
    }
    finally {
      try {
        SwingUtilities.invokeAndWait(new Runnable() {
          @Override
          public void run() {
            cleanupApplicationCaches(getProject());
            resetAllFields();
          }
        });
      }
      catch (Throwable e) {
        // Ignore
      }
    }
  }

  private void runBareImpl() throws Throwable {
    final Throwable[] throwables = new Throwable[1];
    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        ourTestThread = Thread.currentThread();
        ourTestTime = getTimeRequired();
        try {
          try {
            setUp();
          }
          catch (Throwable e) {
            CompositeException result = new CompositeException(e);
            try {
              tearDown();
            }
            catch (Throwable th) {
              result.add(th);
            }
            throw result;
          }
          try {
            myAssertionsInTestDetected = true;
            runTest();
            myAssertionsInTestDetected = false;
          }
          catch (Throwable e) {
            throwables[0] = e;
            throw e;
          }
          finally {
            tearDown();
          }
        }
        catch (Throwable throwable) {
          if (throwables[0] == null) {  // report tearDown() problems if only no exceptions thrown from runTest()
            throwables[0] = throwable;
          }
        }
        finally {
          ourTestThread = null;
        }
      }
    };

    runBareRunnable(runnable);

    if (IdeaLogger.ourErrorsOccurred != null) {
      throw IdeaLogger.ourErrorsOccurred;
    }

    if (throwables[0] != null) {
      throw throwables[0];
    }

    // just to make sure all deferred Runnable's to finish
    waitForAllLaters();
    if (IdeaLogger.ourErrorsOccurred != null) {
      throw IdeaLogger.ourErrorsOccurred;
    }

    /*
    if (++LEAK_WALKS % 1 == 0) {
      LeakHunter.checkLeak(ApplicationManager.getApplication(), ProjectImpl.class, new Processor<ProjectImpl>() {
        @Override
        public boolean process(ProjectImpl project) {
          return !project.isDefault() && !LightPlatformTestCase.isLight(project);
        }
      });
    }
    */
  }

  private static int LEAK_WALKS;

  private static void waitForAllLaters() throws InterruptedException, InvocationTargetException {
    for (int i = 0; i < 3; i++) {
      SwingUtilities.invokeAndWait(EmptyRunnable.getInstance());
    }
  }

  protected boolean isRunInEdt() {
    return true;
  }

  protected void runBareRunnable(Runnable runnable) throws Throwable {
    if (isRunInEdt()) {
      SwingUtilities.invokeAndWait(runnable);
    }
    else {
      runnable.run();
    }
  }

  protected boolean isRunInWriteAction() {
    return true;
  }

  @Override
  protected void invokeTestRunnable(@NotNull final Runnable runnable) throws Exception {
    final Exception[] e = new Exception[1];
    Runnable runnable1 = new Runnable() {
      @Override
      public void run() {
        try {
          if (ApplicationManager.getApplication().isDispatchThread() && isRunInWriteAction()) {
            ApplicationManager.getApplication().runWriteAction(runnable);
          }
          else {
            runnable.run();
          }
        }
        catch (Exception e1) {
          e[0] = e1;
        }
      }
    };

    if (annotatedWith(WrapInCommand.class)) {
      CommandProcessor.getInstance().executeCommand(myProject, runnable1, "", null);
    }
    else {
      runnable1.run();
    }

    if (e[0] != null) {
      throw e[0];
    }
  }

  @Override
  public Object getData(String dataId) {
    return myProject == null ? null : new TestDataProvider(myProject).getData(dataId);
  }

  public static File createTempDir(@NonNls final String prefix) throws IOException {
    return createTempDir(prefix, true);
  }

  public static File createTempDir(@NonNls final String prefix, final boolean refresh) throws IOException {
    final File tempDirectory = FileUtilRt.createTempDirectory(TEST_DIR_PREFIX + prefix, null, false);
    myFilesToDelete.add(tempDirectory);
    if (refresh) {
      getVirtualFile(tempDirectory);
    }
    return tempDirectory;
  }

  @Nullable
  protected static VirtualFile getVirtualFile(final File file) {
    return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
  }

  protected File createTempDirectory() throws IOException {
    return createTempDir(getTestName(true));
  }

  protected File createTempDirectory(final boolean refresh) throws IOException {
    return createTempDir(getTestName(true), refresh);
  }

  protected File createTempFile(String name, @Nullable String text) throws IOException {
    File directory = createTempDirectory();
    File file = new File(directory, name);
    if (!file.createNewFile()) {
      throw new IOException("Can't create " + file);
    }
    if (text != null) {
      FileUtil.writeToFile(file, text);
    }
    return file;
  }

  public static void setContentOnDisk(@NotNull File file, byte[] bom, @NotNull String content, @NotNull Charset charset) throws IOException {
    FileOutputStream stream = new FileOutputStream(file);
    if (bom != null) {
      stream.write(bom);
    }
    OutputStreamWriter writer = new OutputStreamWriter(stream, charset);
    try {
      writer.write(content);
    }
    finally {
      writer.close();
    }
  }

  public static VirtualFile createTempFile(@NonNls @NotNull String ext, @Nullable byte[] bom, @NonNls @NotNull String content, @NotNull Charset charset) throws IOException {
    File temp = FileUtil.createTempFile("copy", "." + ext);
    setContentOnDisk(temp, bom, content, charset);

    myFilesToDelete.add(temp);
    final VirtualFile file = getVirtualFile(temp);
    assert file != null : temp;
    return file;
  }

  @Nullable
  protected PsiFile getPsiFile(final Document document) {
    return PsiDocumentManager.getInstance(getProject()).getPsiFile(document);
  }

  /**
   * @deprecated calling this method is no longer necessary
   */
  public static void initPlatformLangPrefix() {
    initPlatformPrefix(IDEA_MARKER_CLASS, "PlatformLangXml");
  }

  /**
   * This is the main point to set up your platform prefix. This allows you to use some sub-set of
   * core plugin descriptors to make initialization faster (e.g. for running tests in classpath of the module where the test is located).
   * It is calculated by some marker class presence in classpath.
   * Note that it applies NEGATIVE logic for detection: prefix will be set if only marker class
   * is NOT present in classpath.
   * Also, only the very FIRST call to this method will take effect.
   *
   * @param classToTest marker class qualified name e.g. {@link #IDEA_MARKER_CLASS}.
   * @param prefix platform prefix to be set up if marker class not found in classpath.
   * @deprecated calling this method is no longer necessary
   */
  public static void initPlatformPrefix(String classToTest, String prefix) {
    if (!ourPlatformPrefixInitialized) {
      ourPlatformPrefixInitialized = true;
      boolean isUltimate = true;
      try {
        PlatformTestCase.class.getClassLoader().loadClass(classToTest);
      }
      catch (ClassNotFoundException e) {
        isUltimate = false;
      }
      if (!isUltimate) {
        setPlatformPrefix(prefix);
      }
    }
  }

  private static void setPlatformPrefix(String prefix) {
    System.setProperty(PlatformUtils.PLATFORM_PREFIX_KEY, prefix);
    ourPlatformPrefixInitialized = true;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.METHOD, ElementType.TYPE})
  public @interface WrapInCommand {
  }

  protected static VirtualFile createChildData(@NotNull final VirtualFile dir, @NotNull @NonNls final String name) {
    return new WriteAction<VirtualFile>() {
      @Override
      protected void run(@NotNull Result<VirtualFile> result) throws Throwable {
        result.setResult(dir.createChildData(null, name));
      }
    }.execute().throwException().getResultObject();
  }

  protected static VirtualFile createChildDirectory(@NotNull final VirtualFile dir, @NotNull @NonNls final String name) {
    return new WriteAction<VirtualFile>() {
      @Override
      protected void run(@NotNull Result<VirtualFile> result) throws Throwable {
        result.setResult(dir.createChildDirectory(null, name));
      }
    }.execute().throwException().getResultObject();
  }

  protected static void delete(@NotNull final VirtualFile file) {
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        try {
          file.delete(null);
        }
        catch (IOException e) {
          fail();
        }
      }
    });
  }

  protected static void rename(@NotNull final VirtualFile vFile1, @NotNull final String newName) {
    new WriteCommandAction.Simple(null) {
      @Override
      protected void run() throws Throwable {
        vFile1.rename(this, newName);
      }
    }.execute().throwException();
  }

  protected static void move(@NotNull final VirtualFile vFile1, @NotNull final VirtualFile newFile) {
    new WriteCommandAction.Simple(null) {
      @Override
      protected void run() throws Throwable {
        vFile1.move(this, newFile);
      }
    }.execute().throwException();
  }

  protected static VirtualFile copy(@NotNull final VirtualFile file, @NotNull final VirtualFile newParent, @NotNull final String copyName) {
    final VirtualFile[] copy = new VirtualFile[1];

    new WriteCommandAction.Simple(null) {
      @Override
      protected void run() throws Throwable {
        copy[0] = file.copy(this, newParent, copyName);
      }
    }.execute().throwException();
    return copy[0];
  }

  public static void setFileText(@NotNull final VirtualFile file, @NotNull final String text) throws IOException {
    new WriteAction() {
      @Override
      protected void run(@NotNull Result result) throws Throwable {
        VfsUtil.saveText(file, text);
      }
    }.execute().throwException();
  }

  public static void setBinaryContent(final VirtualFile file, final byte[] content) {
    new WriteAction() {
      @Override
      protected void run(@NotNull Result result) throws Throwable {
        file.setBinaryContent(content);
      }
    }.execute().throwException();
  }
}
