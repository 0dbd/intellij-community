/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package org.jetbrains.idea.devkit.testAssistant;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.execution.Location;
import com.intellij.execution.junit.JUnitUtil;
import com.intellij.execution.junit2.PsiMemberParameterizedLocation;
import com.intellij.execution.testframework.TestTreeViewAction;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.Parameterized;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * @author yole
 */
public class NavigateToTestDataAction extends AnAction implements TestTreeViewAction {
  @Override
  public void actionPerformed(AnActionEvent e) {
    final DataContext dataContext = e.getDataContext();
    final Project project = CommonDataKeys.PROJECT.getData(dataContext);
    List<String> fileNames = findTestDataFiles(dataContext);
    if (fileNames == null || fileNames.isEmpty()) {
      String message = "Cannot find testdata files for class";
      final Notification notification = new Notification("testdata", "Found no testdata files", message, NotificationType.INFORMATION);
      Notifications.Bus.notify(notification, project);
    }
    else {
      final Editor editor = e.getData(CommonDataKeys.EDITOR);
      final JBPopupFactory popupFactory = JBPopupFactory.getInstance();
      final RelativePoint point = editor != null ? popupFactory.guessBestPopupLocation(editor) : popupFactory.guessBestPopupLocation(dataContext);
      
      TestDataNavigationHandler.navigate(point, fileNames, project);
    }
  }

  @Nullable
  static List<String> findTestDataFiles(@NotNull DataContext context) {
    final PsiMethod method = findTargetMethod(context);
    if (method == null) {
      return null;
    }
    final String name = method.getName();

    if (name.startsWith("test")) {
      String testDataPath = TestDataLineMarkerProvider.getTestDataBasePath(method.getContainingClass());
      final TestDataReferenceCollector collector = new TestDataReferenceCollector(testDataPath, name.substring(4));
      return collector.collectTestDataReferences(method);
    }

    final Location<?> location = Location.DATA_KEY.getData(context);
    if (location instanceof PsiMemberParameterizedLocation) {
      PsiClass containingClass = ((PsiMemberParameterizedLocation)location).getContainingClass();
      if (containingClass == null) {
        containingClass = PsiTreeUtil.getParentOfType(location.getPsiElement(), PsiClass.class, false);
      }
      if (containingClass != null) {
        final PsiAnnotation annotation = AnnotationUtil.findAnnotationInHierarchy(containingClass, Collections.singleton(JUnitUtil.RUN_WITH));
        if (annotation != null) {
          final PsiAnnotationMemberValue memberValue = annotation.findAttributeValue("value");
          if (memberValue instanceof PsiClassObjectAccessExpression) {
            final PsiTypeElement operand = ((PsiClassObjectAccessExpression)memberValue).getOperand();
            if (operand.getType().equalsToText(Parameterized.class.getName())) {
              final String testDataPath = TestDataLineMarkerProvider.getTestDataBasePath(containingClass);
              final String paramSetName = ((PsiMemberParameterizedLocation)location).getParamSetName();
              final String baseFileName = StringUtil.trimEnd(StringUtil.trimStart(paramSetName, "["), "]");
              return TestDataGuessByExistingFilesUtil.suggestTestDataFiles(baseFileName, testDataPath, containingClass);
            }
          }
        }
      }
    }

    return null;
  }

  @Override
  public void update(AnActionEvent e) {
    e.getPresentation().setEnabled(findTargetMethod(e.getDataContext()) != null);
  }

  @Nullable
  private static PsiMethod findTargetMethod(@NotNull DataContext context) {
    final Editor editor = CommonDataKeys.EDITOR.getData(context);
    final PsiFile file = CommonDataKeys.PSI_FILE.getData(context);
    if (file != null && editor != null) {
      PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
      return PsiTreeUtil.getParentOfType(element, PsiMethod.class);
    }

    final Location<?> location = Location.DATA_KEY.getData(context);
    if (location != null) {
      final PsiElement element = location.getPsiElement();
      if (element instanceof PsiMethod) {
        return (PsiMethod)element;
      }
    }
    return null;
  }
}
