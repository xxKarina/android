/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.android.tools.idea.run.editor;

import com.android.annotations.VisibleForTesting;
import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.idea.gradle.project.model.AndroidModuleModel;
import com.android.tools.idea.run.AndroidRunConfigurationBase;
import com.android.tools.idea.run.ConfigurationSpecificEditor;
import com.android.tools.idea.run.ValidationError;
import com.android.tools.idea.testartifacts.instrumented.AndroidTestRunConfiguration;
import com.google.common.base.Predicate;
import com.google.common.collect.Ordering;
import com.intellij.application.options.ModulesComboBox;
import com.intellij.execution.ui.ConfigurationModuleSelector;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.PanelWithAnchor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;

public class AndroidRunConfigurationEditor<T extends AndroidRunConfigurationBase> extends SettingsEditor<T> implements PanelWithAnchor,
                                                                                                                       ActionListener {
  private JPanel myPanel;
  protected JBTabbedPane myTabbedPane;
  private JBLabel myModuleJBLabel;
  private ModulesComboBox myModulesComboBox;

  // application run parameters or test run parameters
  private JPanel myConfigurationSpecificPanel;

  private final DeploymentTargetOptions myDeploymentTargetOptions;

  // Misc. options tab
  private JCheckBox myClearLogCheckBox;
  private JCheckBox myShowLogcatCheckBox;
  private JCheckBox mySkipNoOpApkInstallation;
  private JCheckBox myForceStopRunningApplicationCheckBox;
  private HyperlinkLabel myOldVersionLabel;

  private JComponent anchor;

  private final ConfigurationModuleSelector myModuleSelector;
  private ConfigurationSpecificEditor<T> myConfigurationSpecificEditor;

  private AndroidDebuggerPanel myAndroidDebuggerPanel;
  private final AndroidProfilersPanel myAndroidProfilersPanel;

  public AndroidRunConfigurationEditor(final Project project, final Predicate<AndroidFacet> libraryProjectValidator, T config) {
    Disposer.register(project, this);
    myModuleSelector = new ConfigurationModuleSelector(project, myModulesComboBox) {
      @Override
      public boolean isModuleAccepted(Module module) {
        if (module == null || !super.isModuleAccepted(module)) {
          return false;
        }

        final AndroidFacet facet = AndroidFacet.getInstance(module);
        if (facet == null) {
          return false;
        }

        return !facet.getConfiguration().isLibraryProject() || libraryProjectValidator.apply(facet);
      }
    };
    myModulesComboBox.addActionListener(this);

    if (StudioFlags.SELECT_DEVICE_SNAPSHOT_COMBO_BOX_VISIBLE.get()) {
      myDeploymentTargetOptions = null;
    }
    else {
      myDeploymentTargetOptions = new DeploymentTargetOptions(config, this, project);
      myDeploymentTargetOptions.addTo((Container)myTabbedPane.getComponentAt(0));
    }

    if (config instanceof AndroidTestRunConfiguration) {
      // The application is always force stopped when running `am instrument`. See AndroidTestRunConfiguration#getLaunchOptions().
      myForceStopRunningApplicationCheckBox.setVisible(false);
    }
    else {
      mySkipNoOpApkInstallation.addActionListener(e -> {
        if (mySkipNoOpApkInstallation == e.getSource()) {
          myForceStopRunningApplicationCheckBox.setEnabled(mySkipNoOpApkInstallation.isSelected());
        }
      });
    }

    AndroidDebuggerContext androidDebuggerContext = config.getAndroidDebuggerContext();
    myModulesComboBox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        androidDebuggerContext.setDebuggeeModule(myModulesComboBox.getSelectedModule());
      }
    });

    if (androidDebuggerContext.getAndroidDebuggers().size() > 1) {
      myAndroidDebuggerPanel = new AndroidDebuggerPanel(config, androidDebuggerContext);
      myTabbedPane.add("Debugger", myAndroidDebuggerPanel.getComponent());
    }

    myAndroidProfilersPanel = new AndroidProfilersPanel(project, config.getProfilerState());
    myTabbedPane.add("Profiling", myAndroidProfilersPanel.getComponent());

    checkValidationResults(config.validate(null));
  }

  public void setConfigurationSpecificEditor(ConfigurationSpecificEditor<T> configurationSpecificEditor) {
    myConfigurationSpecificEditor = configurationSpecificEditor;
    myConfigurationSpecificPanel.add(configurationSpecificEditor.getComponent());
    setAnchor(myConfigurationSpecificEditor.getAnchor());
    myShowLogcatCheckBox.setVisible(configurationSpecificEditor instanceof ApplicationRunParameters);
  }

  /**
   * Allows the editor UI to response based on any validation errors.
   * The {@link ValidationError.Category} with the most severe errors should be responded to first.
   */
  private void checkValidationResults(@NotNull List<ValidationError> errors) {
    if (errors.isEmpty()) {
      return;
    }

    ValidationError topError = Ordering.natural().max(errors);
    if (ValidationError.Category.PROFILER.equals(topError.getCategory())) {
      myTabbedPane.setSelectedComponent(myAndroidProfilersPanel.getComponent());
    }
  }

  @Override
  public JComponent getAnchor() {
    return anchor;
  }

  @Override
  public void setAnchor(JComponent anchor) {
    this.anchor = anchor;
    myModuleJBLabel.setAnchor(anchor);
  }

  @Override
  protected void resetEditorFrom(@NotNull T configuration) {
    // Set configurations before resetting the module selector to avoid premature calls to setFacet.
    myModuleSelector.reset(configuration);

    if (!StudioFlags.SELECT_DEVICE_SNAPSHOT_COMBO_BOX_VISIBLE.get()) {
      myDeploymentTargetOptions.resetFrom(configuration);
    }

    myClearLogCheckBox.setSelected(configuration.CLEAR_LOGCAT);
    myShowLogcatCheckBox.setSelected(configuration.SHOW_LOGCAT_AUTOMATICALLY);
    mySkipNoOpApkInstallation.setSelected(configuration.SKIP_NOOP_APK_INSTALLATIONS);
    myForceStopRunningApplicationCheckBox.setSelected(configuration.FORCE_STOP_RUNNING_APP);

    myConfigurationSpecificEditor.resetFrom(configuration);

    if (myAndroidDebuggerPanel != null) {
      myAndroidDebuggerPanel.resetFrom(configuration.getAndroidDebuggerContext());
    }
    myAndroidProfilersPanel.resetFrom(configuration.getProfilerState());
  }

  @Override
  protected void applyEditorTo(@NotNull T configuration) {
    myModuleSelector.applyTo(configuration);

    if (!StudioFlags.SELECT_DEVICE_SNAPSHOT_COMBO_BOX_VISIBLE.get()) {
      myDeploymentTargetOptions.applyTo(configuration);
    }

    configuration.CLEAR_LOGCAT = myClearLogCheckBox.isSelected();
    configuration.SHOW_LOGCAT_AUTOMATICALLY = myShowLogcatCheckBox.isSelected();
    configuration.SKIP_NOOP_APK_INSTALLATIONS = mySkipNoOpApkInstallation.isSelected();
    configuration.FORCE_STOP_RUNNING_APP = myForceStopRunningApplicationCheckBox.isSelected();

    myConfigurationSpecificEditor.applyTo(configuration);

    if (myAndroidDebuggerPanel != null) {
      myAndroidDebuggerPanel.applyTo(configuration.getAndroidDebuggerContext());
    }
    myAndroidProfilersPanel.applyTo(configuration.getProfilerState());
  }

  @Override
  @NotNull
  protected JComponent createEditor() {
    return myPanel;
  }

  public ConfigurationModuleSelector getModuleSelector() {
    return myModuleSelector;
  }

  @NotNull
  JComboBox getModuleComboBox() {
    return myModulesComboBox;
  }

  @Override
  public void actionPerformed(ActionEvent e) {
    if (e.getSource() == myModulesComboBox) {
      updateLinkState();
      if (myConfigurationSpecificEditor instanceof ApplicationRunParameters) {
        ((ApplicationRunParameters)myConfigurationSpecificEditor).onModuleChanged();
      }
    }
  }

  private void createUIComponents() {
    // JBColor keeps a strong reference to its parameter func, so, using a lambda avoids this reference and fixes a leak
    myOldVersionLabel = new HyperlinkLabel();

    setSyncLinkMessage("");
  }

  private void setSyncLinkMessage(@NotNull String syncMessage) {
    myOldVersionLabel.setHyperlinkText("Instant Run requires a newer version of the Gradle plugin. ", "Update Project", " " + syncMessage);
    myOldVersionLabel.repaint();
  }

  private void updateLinkState() {
    Module module = getModuleSelector().getModule();
    if (module == null) {
      myOldVersionLabel.setVisible(false);
      return;
    }

    AndroidModuleModel model = AndroidModuleModel.get(module);
    if (model == null) {
      myOldVersionLabel.setVisible(false);
      return;
    }

    myOldVersionLabel.setVisible(true);
  }

  @VisibleForTesting
  public ConfigurationSpecificEditor<T> getConfigurationSpecificEditor() {
    return myConfigurationSpecificEditor;
  }
}
