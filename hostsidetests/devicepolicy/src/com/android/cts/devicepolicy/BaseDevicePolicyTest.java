/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.cts.devicepolicy;

import com.android.cts.tradefed.build.CtsBuildHelper;
import com.android.ddmlib.Log.LogLevel;
import com.android.ddmlib.testrunner.InstrumentationResultParser;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.ddmlib.testrunner.TestResult;
import com.android.ddmlib.testrunner.TestResult.TestStatus;
import com.android.ddmlib.testrunner.TestRunResult;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.CollectingTestListener;
import com.android.tradefed.testtype.DeviceTestCase;
import com.android.tradefed.testtype.IBuildReceiver;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Base class for device policy tests. It offers utility methods to run tests, set device or profile
 * owner, etc.
 */
public class BaseDevicePolicyTest extends DeviceTestCase implements IBuildReceiver {

    private static final String RUNNER = "android.test.InstrumentationTestRunner";

    private static final String[] REQUIRED_DEVICE_FEATURES = new String[] {
        "android.software.managed_users",
        "android.software.device_admin" };

    private CtsBuildHelper mCtsBuild;

    protected boolean mHasFeature;

    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mCtsBuild = CtsBuildHelper.createBuildHelper(buildInfo);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        assertNotNull(mCtsBuild);  // ensure build has been set before test is run.
        mHasFeature = getDevice().getApiLevel() >= 21 /* Build.VERSION_CODES.L */
                && hasDeviceFeatures(REQUIRED_DEVICE_FEATURES);
    }

    protected void installApp(String fileName)
            throws FileNotFoundException, DeviceNotAvailableException {
        String installResult = getDevice().installPackage(mCtsBuild.getTestApp(fileName), true);
        assertNull(String.format("Failed to install %s, Reason: %s", fileName, installResult),
                installResult);
    }

    /** Initializes the user with the given id. This is required so that apps can run on it. */
    protected void startUser(int userId) throws DeviceNotAvailableException {
        String command = "am start-user " + userId;
        String commandOutput = getDevice().executeShellCommand(command);
        CLog.logAndDisplay(LogLevel.INFO, "Output for command " + command + ": " + commandOutput);
        assertTrue(commandOutput.startsWith("Success:"));
    }

    protected int getMaxNumberOfUsersSupported() throws DeviceNotAvailableException {
        // TODO: move this to ITestDevice once it supports users
        String command = "pm get-max-users";
        String commandOutput = getDevice().executeShellCommand(command);
        try {
            return Integer.parseInt(commandOutput.substring(commandOutput.lastIndexOf(" ")).trim());
        } catch (NumberFormatException e) {
            fail("Failed to parse result: " + commandOutput);
        }
        return 0;
    }

    protected ArrayList<Integer> listUsers() throws DeviceNotAvailableException {
        String command = "pm list users";
        String commandOutput = getDevice().executeShellCommand(command);

        // Extract the id of all existing users.
        String[] lines = commandOutput.split("\\r?\\n");
        assertTrue(lines.length >= 1);
        assertEquals(lines[0], "Users:");

        ArrayList<Integer> users = new ArrayList<Integer>();
        for (int i = 1; i < lines.length; i++) {
            // Individual user is printed out like this:
            // \tUserInfo{$id$:$name$:$Integer.toHexString(flags)$} [running]
            String[] tokens = lines[i].split("\\{|\\}|:");
            assertTrue(tokens.length == 4 || tokens.length == 5);
            users.add(Integer.parseInt(tokens[1]));
        }
        return users;
    }

    protected void removeUser(int userId) throws DeviceNotAvailableException  {
        String removeUserCommand = "pm remove-user " + userId;
        CLog.logAndDisplay(LogLevel.INFO, "Output for command " + removeUserCommand + ": "
                + getDevice().executeShellCommand(removeUserCommand));
    }

    /** Returns true if the specified tests passed. Tests are run as user owner. */
    protected boolean runDeviceTests(String pkgName, @Nullable String testClassName)
            throws DeviceNotAvailableException {
        return runDeviceTests(pkgName, testClassName, null /*testMethodName*/, null /*userId*/);
    }

    /** Returns true if the specified tests passed. Tests are run as given user. */
    protected boolean runDeviceTestsAsUser(
            String pkgName, @Nullable String testClassName, int userId)
            throws DeviceNotAvailableException {
        return runDeviceTests(pkgName, testClassName, null /*testMethodName*/, userId);
    }

    private boolean runDeviceTests(String pkgName, @Nullable String testClassName,
            @Nullable String testMethodName, @Nullable Integer userId)
                    throws DeviceNotAvailableException {
        TestRunResult runResult = (userId == null)
                ? doRunTests(pkgName, testClassName, testMethodName)
                : doRunTestsAsUser(pkgName, testClassName, testMethodName, userId);
        printTestResult(runResult);
        return !runResult.hasFailedTests() && runResult.getNumTestsInState(TestStatus.PASSED) > 0;
    }

    /** Helper method to run tests and return the listener that collected the results. */
    private TestRunResult doRunTests(
            String pkgName, String testClassName,
            String testMethodName) throws DeviceNotAvailableException {
        RemoteAndroidTestRunner testRunner = new RemoteAndroidTestRunner(
                pkgName, RUNNER, getDevice().getIDevice());
        if (testClassName != null && testMethodName != null) {
            testRunner.setMethodName(testClassName, testMethodName);
        } else if (testClassName != null) {
            testRunner.setClassName(testClassName);
        }

        CollectingTestListener listener = new CollectingTestListener();
        assertTrue(getDevice().runInstrumentationTests(testRunner, listener));
        return listener.getCurrentRunResults();
    }

    private TestRunResult doRunTestsAsUser(String pkgName, @Nullable String testClassName,
            @Nullable String testMethodName, int userId)
            throws DeviceNotAvailableException {
        // TODO: move this to RemoteAndroidTestRunner once it supports users. Should be straight
        // forward to add a RemoteAndroidTestRunner.setUser(userId) method. Then we can merge both
        // doRunTests* methods.
        StringBuilder testsToRun = new StringBuilder();
        if (testClassName != null) {
            testsToRun.append("-e class " + testClassName);
            if (testMethodName != null) {
                testsToRun.append("#" + testMethodName);
            }
        }
        String command = "am instrument --user " + userId + " -w -r " + testsToRun + " "
                + pkgName + "/" + RUNNER;
        CLog.i("Running " + command);

        CollectingTestListener listener = new CollectingTestListener();
        InstrumentationResultParser parser = new InstrumentationResultParser(pkgName, listener);
        getDevice().executeShellCommand(command, parser);
        return listener.getCurrentRunResults();
    }

    private void printTestResult(TestRunResult runResult) {
        for (Map.Entry<TestIdentifier, TestResult> testEntry :
                runResult.getTestResults().entrySet()) {
            TestResult testResult = testEntry.getValue();
            CLog.logAndDisplay(LogLevel.INFO,
                    "Test " + testEntry.getKey() + ": " + testResult.getStatus());
            if (testResult.getStatus() != TestStatus.PASSED) {
                CLog.logAndDisplay(LogLevel.WARN, testResult.getStackTrace());
            }
        }
    }

    private boolean hasDeviceFeatures(String[] requiredFeatures)
            throws DeviceNotAvailableException {
        // TODO: Move this logic to ITestDevice.
        String command = "pm list features";
        String commandOutput = getDevice().executeShellCommand(command);

        // Extract the id of the new user.
        HashSet<String> availableFeatures = new HashSet<String>();
        for (String feature: commandOutput.split("\\s+")) {
            // Each line in the output of the command has the format "feature:{FEATURE_VALUE}".
            String[] tokens = feature.split(":");
            assertTrue(tokens.length > 1);
            assertEquals("feature", tokens[0]);
            availableFeatures.add(tokens[1]);
        }

        for (String requiredFeature : requiredFeatures) {
            if(!availableFeatures.contains(requiredFeature)) {
                CLog.logAndDisplay(LogLevel.INFO, "Device doesn't have required feature "
                        + requiredFeature + ". Tests won't run.");
                return false;
            }
        }
        return true;
    }
}