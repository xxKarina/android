/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.profilers;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.TimeoutException;
import com.android.sdklib.AndroidVersion;
import com.android.sdklib.devices.Abi;
import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.idea.profilers.profilingconfig.CpuProfilerConfigConverter;
import com.android.tools.idea.project.AndroidNotification;
import com.android.tools.idea.run.AndroidLaunchTaskContributor;
import com.android.tools.idea.run.AndroidRunConfigurationBase;
import com.android.tools.idea.run.ConsolePrinter;
import com.android.tools.idea.run.LaunchOptions;
import com.android.tools.idea.run.profiler.CpuProfilerConfig;
import com.android.tools.idea.run.profiler.CpuProfilerConfigsState;
import com.android.tools.idea.run.tasks.LaunchTask;
import com.android.tools.idea.run.tasks.LaunchTaskDurations;
import com.android.tools.idea.run.util.LaunchStatus;
import com.android.tools.profiler.proto.Common;
import com.android.tools.profiler.proto.CpuProfiler;
import com.android.tools.profiler.proto.Profiler;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * A {@link AndroidLaunchTaskContributor} specific to profiler. For example, this contributor provides "--attach-agent $agentArgs"
 * extra option to "am start ..." command.
 */
public final class AndroidProfilerLaunchTaskContributor implements AndroidLaunchTaskContributor {
  private static Logger getLogger() {
    return Logger.getInstance(AndroidProfilerLaunchTaskContributor.class);
  }

  @NotNull
  @Override
  public LaunchTask getTask(@NotNull Module module, @NotNull String applicationId, @NotNull LaunchOptions launchOptions) {
    return new AndroidProfilerToolWindowLaunchTask(module, launchOptions);
  }

  @NotNull
  @Override
  public String getAmStartOptions(@NotNull Module module, @NotNull String applicationId, @NotNull LaunchOptions launchOptions,
                                  @NotNull IDevice device) {
    if (!isProfilerLaunch(launchOptions)) {
      // Not a profile action
      return "";
    }

    Project project = module.getProject();
    ProfilerService profilerService = ProfilerService.getInstance(project);
    if (profilerService == null) {
      // Profiler cannot be run.
      return "";
    }

    long deviceId;
    try {
      deviceId = waitForPerfd(device, profilerService);
    }
    catch (InterruptedException | TimeoutException e) {
      getLogger().debug(e);
      // Don't attach JVMTI agent for now, there is a chance that it will be attached during runtime.
      return "";
    }

    StringBuilder args = new StringBuilder(getAttachAgentArgs(applicationId, profilerService, device, deviceId));
    args.append(" ").append(startStartupProfiling(applicationId, module, profilerService, device, deviceId));
    return args.toString();
  }

  @NotNull
  private static String getAttachAgentArgs(@NotNull String appPackageName,
                                           @NotNull ProfilerService profilerService,
                                           @NotNull IDevice device,
                                           long deviceId) {
    // --attach-agent flag was introduced from android API level 27.
    if (!StudioFlags.PROFILER_USE_JVMTI.get() || device.getVersion().getFeatureLevel() < AndroidVersion.VersionCodes.O_MR1) {
      return "";
    }
    Profiler.ConfigureStartupAgentResponse response = profilerService.getProfilerClient().getProfilerClient()
                                                                     .configureStartupAgent(
                                                                       Profiler.ConfigureStartupAgentRequest.newBuilder()
                                                                                                            .setDeviceId(deviceId)
                                                                                                            // TODO: Find a way of finding the correct ABI
                                                                                                            .setAgentLibFileName(
                                                                                                              getAbiDependentLibPerfaName(
                                                                                                                device))
                                                                                                            .setAppPackageName(
                                                                                                              appPackageName).build());
    return response.getAgentArgs().isEmpty() ? "" : "--attach-agent " + response.getAgentArgs();
  }

  /**
   * Starts startup profiling by RPC call to perfd.
   *
   * @return arguments used with --start-profiler flag, i.e "--start-profiler $filePath --sampling 100 --streaming",
   * the result is an empty string, when either startup CPU profiling is not enabled
   * or the selected CPU configuration is not an ART profiling.
   */
  @NotNull
  private static String startStartupProfiling(@NotNull String appPackageName,
                                              @NotNull Module module,
                                              @NotNull ProfilerService profilerService,
                                              @NotNull IDevice device,
                                              long deviceId) {
    if (!StudioFlags.PROFILER_STARTUP_CPU_PROFILING.get()) {
      return "";
    }

    AndroidRunConfigurationBase runConfig = getSelectedRunConfiguration(module.getProject());
    if (runConfig == null || !runConfig.getProfilerState().STARTUP_CPU_PROFILING_ENABLED) {
      return "";
    }

    String configName = runConfig.getProfilerState().STARTUP_CPU_PROFILING_CONFIGURATION_NAME;
    CpuProfilerConfig startupConfig = CpuProfilerConfigsState.getInstance(module.getProject()).getConfigByName(configName);
    if (startupConfig == null) {
      return "";
    }

    if (!isAtLeastO(device)) {
      AndroidNotification.getInstance(module.getProject()).showBalloon("Startup CPU Profiling",
                                                                       "Starting a method trace recording on startup is only " +
                                                                       "supported on devices with API levels 26 and higher.",
                                                                       NotificationType.WARNING);
      return "";
    }

    CpuProfiler.StartupProfilingRequest.Builder requestBuilder = CpuProfiler.StartupProfilingRequest
      .newBuilder()
      .setAppPackage(appPackageName)
      .setDeviceId(deviceId)
      .setConfiguration(CpuProfilerConfigConverter.toProto(startupConfig));

    if (requestBuilder.getConfiguration().getProfilerType() == CpuProfiler.CpuProfilerType.SIMPLEPERF) {
      requestBuilder.setAbiCpuArch(getSimpleperfAbi(device));
    }

    CpuProfiler.StartupProfilingResponse response = profilerService
      .getProfilerClient().getCpuClient()
      .startStartupProfiling(requestBuilder.build());

    if (response.getFilePath().isEmpty() || requestBuilder.getConfiguration().getProfilerType() != CpuProfiler.CpuProfilerType.ART) {
      return "";
    }

    StringBuilder argsBuilder = new StringBuilder("--start-profiler ").append(response.getFilePath());
    if (startupConfig.getTechnology() == CpuProfilerConfig.Technology.SAMPLED_JAVA) {
      argsBuilder.append(" --sampling ").append(startupConfig.getSamplingIntervalUs());
    }

    argsBuilder.append(" --streaming");
    return argsBuilder.toString();
  }

  private static boolean isAtLeastO(@NotNull IDevice device) {
    return device.getVersion().getFeatureLevel() >= AndroidVersion.VersionCodes.O;
  }

  @Nullable
  private static AndroidRunConfigurationBase getSelectedRunConfiguration(@NotNull Project project) {
    RunnerAndConfigurationSettings settings = RunManager.getInstance(project).getSelectedConfiguration();
    if (settings != null && settings.getConfiguration() instanceof AndroidRunConfigurationBase) {
      return (AndroidRunConfigurationBase)settings.getConfiguration();
    }
    return null;
  }

  /**
   * Waits for perfd to come online for maximum 1 minute.
   *
   * @return ID of device, i.e {@link Common.Device#getDeviceId()}
   */
  private static long waitForPerfd(@NotNull IDevice device, @NotNull ProfilerService profilerService)
    throws InterruptedException, TimeoutException {
    // Wait for perfd to come online for 1 minute.
    for (int i = 0; i < 60; ++i) {
      Common.Device profilerDevice = getProfilerDevice(device, profilerService);
      if (!Common.Device.getDefaultInstance().equals(profilerDevice)) {
        return profilerDevice.getDeviceId();
      }
      Thread.sleep(TimeUnit.SECONDS.toMillis(1));
    }
    throw new TimeoutException("Timeout waiting for perfd");
  }

  /**
   * @return the {@link Common.Device} representation of the input {@link IDevice} if one exists.
   * {@link Common.Device#getDefaultInstance()} otherwise.
   */
  @NotNull
  private static Common.Device getProfilerDevice(@NotNull IDevice device, @NotNull ProfilerService profilerService) {
    Profiler.GetDevicesResponse response =
      profilerService.getProfilerClient().getProfilerClient().getDevices(Profiler.GetDevicesRequest.getDefaultInstance());

    for (Common.Device profilerDevice : response.getDeviceList()) {
      if (profilerDevice.getSerial().equals(device.getSerialNumber())) {
        return profilerDevice;
      }
    }

    return Common.Device.getDefaultInstance();
  }

  @NotNull
  private static String getAbiDependentLibPerfaName(IDevice device) {
    String abi = getBestAbi(device,
                            "plugins/android/resources/perfa",
                            "../../bazel-bin/tools/base/profiler/native/perfa/android",
                            "libperfa.so");
    return abi.isEmpty() ? "" : String.format("libperfa_%s.so", abi);
  }

  @NotNull
  private static String getSimpleperfAbi(IDevice device) {
    return getBestAbi(device,
                      "plugins/android/resources/simpleperf",
                      "../../prebuilts/tools/common/simpleperf",
                      "simpleperf");
  }

  /**
   * @return the most preferred ABI according to {@link IDevice#getAbis()} for which
   * {@param fileName} exists in {@param releaseDir} or {@param devDir}
   */
  @NotNull
  private static String getBestAbi(@NotNull IDevice device,
                                   @NotNull String releaseDir,
                                   @NotNull String devDir,
                                   @NotNull String fileName) {
    File dir = new File(PathManager.getHomePath(), releaseDir);
    if (!dir.exists()) {
      dir = new File(PathManager.getHomePath(), devDir);
    }
    for (String abi : device.getAbis()) {
      File candidate = new File(dir, abi + "/" + fileName);
      if (candidate.exists()) {
        return Abi.getEnum(abi).getCpuArch();
      }
    }
    return "";
  }

  /**
   * @return true if the launch is initiated by the {@link ProfileRunExecutor}. False otherwise.
   */
  private static boolean isProfilerLaunch(@NotNull LaunchOptions options) {
    Object launchValue = options.getExtraOption(ProfileRunExecutor.PROFILER_LAUNCH_OPTION_KEY);
    return launchValue instanceof Boolean && (Boolean)launchValue;
  }

  public static final class AndroidProfilerToolWindowLaunchTask implements LaunchTask {
    @NotNull private final Module myModule;
    @NotNull private final LaunchOptions myLaunchOptions;

    public AndroidProfilerToolWindowLaunchTask(@NotNull Module module, @NotNull LaunchOptions launchOptions) {
      myModule = module;
      myLaunchOptions = launchOptions;
    }

    @NotNull
    @Override
    public String getDescription() {
      return "Presents the Profiler Tool Window";
    }

    @Override
    public int getDuration() {
      return LaunchTaskDurations.LAUNCH_ACTIVITY;
    }

    @Override
    public boolean perform(@NotNull IDevice device, @NotNull LaunchStatus launchStatus, @NotNull ConsolePrinter printer) {
      // We only profile the process that is launched and detected by the profilers after the current device time.
      // This is to avoid profiling the previous application instance in case it is still running.
      long currentDeviceTimeNs = getCurrentDeviceTime(device);
      ApplicationManager.getApplication().invokeLater(
        () -> {
          Project project = myModule.getProject();
          ToolWindow window = ToolWindowManagerEx.getInstanceEx(project).getToolWindow(AndroidProfilerToolWindowFactory.ID);
          if (window != null) {
            window.setShowStripeButton(true);
            // If the window is currently not shown, either if the users click on Run/Debug or if they manually collapse/hide the window,
            // then we shouldn't start profiling the launched app.
            if (window.isVisible()) {
              AndroidProfilerToolWindow profilerToolWindow = AndroidProfilerToolWindowFactory.getProfilerToolWindow(project);
              if (profilerToolWindow != null) {
                profilerToolWindow.profileModule(myModule, device, p -> p.getStartTimestampNs() >= currentDeviceTimeNs);
              }
            }
          }
        });
      return true;
    }

    /**
     * Attempt to get the current time of the device.
     */
    private long getCurrentDeviceTime(@NotNull IDevice device) {
      long startTimeNs = Long.MIN_VALUE;

      // If it's not a profile launch avoid initializing the service as it is an expensive call.
      if (!isProfilerLaunch(myLaunchOptions) && !ProfilerService.isServiceInitialized(myModule.getProject())) {
        return startTimeNs;
      }

      ProfilerService profilerService = ProfilerService.getInstance(myModule.getProject());
      if (profilerService == null) {
        return startTimeNs;
      }

      // If we are launching from the "Profile" action, wait for perfd to start properly to get the time.
      // Note: perfd should have started already from AndroidProfilerLaunchTaskContributor#getAmStartOptions already. This wait might be
      // redundant but harmless.
      long deviceId = -1;
      if (isProfilerLaunch(myLaunchOptions)) {
        try {
          deviceId = waitForPerfd(device, profilerService);
        }
        catch (InterruptedException | TimeoutException e) {
          getLogger().debug(e);
        }
      }
      else {
        // If we are launching from Run/Debug, do not bother waiting for perfd start, but try to get the time anyway in case the profiler
        // is already running.
        deviceId = getProfilerDevice(device, profilerService).getDeviceId();
      }

      Profiler.TimeResponse timeResponse = profilerService.getProfilerClient().getProfilerClient().getCurrentTime(
        Profiler.TimeRequest.newBuilder().setDeviceId(deviceId).build());
      if (!Profiler.TimeResponse.getDefaultInstance().equals(timeResponse)) {
        // Found a valid time response, sets that as the time for detecting when the process is next launched.
        startTimeNs = timeResponse.getTimestampNs();
      }

      return startTimeNs;
    }
  }
}