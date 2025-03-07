/*
 * Tencent is pleased to support the open source community by making Tinker available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.tinker.loader;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager$DexModuleRegisterCallback;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import com.tencent.tinker.loader.shareutil.ShareFileLockHelper;
import com.tencent.tinker.loader.shareutil.SharePatchFileUtil;
import com.tencent.tinker.loader.shareutil.ShareReflectUtil;
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals;
import com.tencent.tinker.loader.shareutil.ShareTinkerLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import dalvik.system.DexFile;


/**
 * Created by tangyinsheng on 2016/11/15.
 */

public final class TinkerDexOptimizer {
    private static final String TAG = "Tinker.ParallelDex";

    private static final String INTERPRET_LOCK_FILE_NAME = "interpret.lock";

    /**
     * Optimize (trigger dexopt or dex2oat) dexes.
     *
     * @param dexFiles
     * @param optimizedDir
     * @param cb
     * @return If all dexes are optimized successfully, return true. Otherwise return false.
     */
    public static boolean optimizeAll(Context context, Collection<File> dexFiles, File optimizedDir,
                                      boolean useDLC, ResultCallback cb) {
        return optimizeAll(context, dexFiles, optimizedDir, false, useDLC, null, cb);
    }

    public static boolean optimizeAll(Context context, Collection<File> dexFiles, File optimizedDir,
                                      boolean useInterpretMode, boolean useDLC,
                                      String targetISA, ResultCallback cb) {
        ArrayList<File> sortList = new ArrayList<>(dexFiles);
        // sort input dexFiles with its file length in reverse order.
        Collections.sort(sortList, new Comparator<File>() {
            @Override
            public int compare(File lhs, File rhs) {
                final long lhsSize = lhs.length();
                final long rhsSize = rhs.length();
                if (lhsSize < rhsSize) {
                    return 1;
                } else if (lhsSize == rhsSize) {
                    return 0;
                } else {
                    return -1;
                }
            }
        });
        for (File dexFile : sortList) {
            OptimizeWorker worker = new OptimizeWorker(context, dexFile, optimizedDir, useInterpretMode,
                  useDLC, targetISA, cb);
            if (!worker.run()) {
                return false;
            }
        }
        return true;
    }

    public interface ResultCallback {
        void onStart(File dexFile, File optimizedDir);

        void onSuccess(File dexFile, File optimizedDir, File optimizedFile);

        void onFailed(File dexFile, File optimizedDir, Throwable thr);
    }

    private static class OptimizeWorker {
        private static String targetISA = null;
        private final Context context;
        private final File dexFile;
        private final File optimizedDir;
        private final boolean useInterpretMode;
        private final boolean useDLC;
        private final ResultCallback callback;

        OptimizeWorker(Context context, File dexFile, File optimizedDir, boolean useInterpretMode,
                       boolean useDLC, String targetISA, ResultCallback cb) {
            this.context = context;
            this.dexFile = dexFile;
            this.optimizedDir = optimizedDir;
            this.useInterpretMode = useInterpretMode;
            this.useDLC = useDLC;
            this.callback = cb;
            this.targetISA = targetISA;
        }

        boolean run() {
            try {
                if (!SharePatchFileUtil.isLegalFile(dexFile)) {
                    if (callback != null) {
                        callback.onFailed(dexFile, optimizedDir,
                                new IOException("dex file " + dexFile.getAbsolutePath() + " is not exist!"));
                        return false;
                    }
                }
                if (callback != null) {
                    callback.onStart(dexFile, optimizedDir);
                }
                String optimizedPath = SharePatchFileUtil.optimizedPathFor(this.dexFile, this.optimizedDir);
                if (!ShareTinkerInternals.isArkHotRuning()) {
                    if (useInterpretMode) {
                        interpretDex2Oat(dexFile.getAbsolutePath(), optimizedPath);
                    } else if (Build.VERSION.SDK_INT >= 26
                            || (Build.VERSION.SDK_INT >= 25 && Build.VERSION.PREVIEW_SDK_INT != 0)) {
                        NewClassLoaderInjector.triggerDex2Oat(context, optimizedDir,
                                                              useDLC, dexFile.getAbsolutePath());
                        // Android Q is significantly slowed down by Fallback Dex Loading procedure, so we
                        // trigger background dexopt to generate executable odex here.
                        triggerPMDexOptOnDemand(context, dexFile.getAbsolutePath(), optimizedPath);
                    } else {
                        DexFile.loadDex(dexFile.getAbsolutePath(), optimizedPath, 0);
                    }
                }
                if (callback != null) {
                    callback.onSuccess(dexFile, optimizedDir, new File(optimizedPath));
                }
            } catch (final Throwable e) {
                ShareTinkerLog.e(TAG, "Failed to optimize dex: " + dexFile.getAbsolutePath(), e);
                if (callback != null) {
                    callback.onFailed(dexFile, optimizedDir, e);
                    return false;
                }
            }
            return true;
        }

        private static void triggerPMDexOptOnDemand(Context context, String dexPath, String oatPath) {
            if (Build.VERSION.SDK_INT < 29) {
                // Only do this trick on Android Q, R devices.
                ShareTinkerLog.w(TAG, "[+] Not API 29, 30 device, skip fixing.");
                return;
            }

            ShareTinkerLog.i(TAG, "[+] Hit target device, do fix logic now.");

            try {
                final File oatFile = new File(oatPath);
                if (oatFile.exists()) {
                    ShareTinkerLog.i(TAG, "[+] Odex file exists, skip bg-dexopt triggering.");
                    return;
                }
                boolean performDexOptSecondarySuccess = true;
                try {
                    performDexOptSecondary(context, oatPath);
                } catch (Throwable thr) {
                    ShareTinkerLog.printErrStackTrace(TAG, thr, "[-] Fail to call performDexOptSecondary.");
                    performDexOptSecondarySuccess = false;
                }
                // Take a rest. And hope any asynchronous mechanism may generate odex we need.
                SystemClock.sleep(1000);
                if (!performDexOptSecondarySuccess || !oatFile.exists()) {
                    if ("huawei".equalsIgnoreCase(Build.MANUFACTURER) || "honor".equalsIgnoreCase(Build.MANUFACTURER)) {
                        registerDexModule(context, dexPath, oatPath);
                    }
                }
                if (oatFile.exists()) {
                    ShareTinkerLog.i(TAG, "[+] Bg-dexopt was triggered successfully.");
                } else {
                    throw new IllegalStateException("Bg-dexopt was triggered, but no odex file was generated.");
                }
            } catch (Throwable thr) {
                ShareTinkerLog.printErrStackTrace(TAG, thr, "[-] Fail to call triggerPMDexOptAsyncOnDemand.");
            }
        }

        public static void performDexOptSecondary(Context context, String oatPath) throws IllegalStateException {
            try {
                ShareTinkerLog.i(TAG, "[+] Start trigger secondary dexopt.");
                final File oatFile = new File(oatPath);
                final int transactionCode = queryPerformDexOptSecondaryTransactionCode();
                final String packageName = context.getPackageName();
                final String targetCompilerFilter = "speed";
                final boolean force = false;

                final Class<?> serviceManagerClazz = Class.forName("android.os.ServiceManager");
                final Method getServiceMethod = ShareReflectUtil.findMethod(serviceManagerClazz, "getService", String.class);
                final IBinder pmBinder = (IBinder) getServiceMethod.invoke(null, "package");
                if (pmBinder == null) {
                    throw new IllegalStateException("Fail to get pm binder.");
                }
                final int maxRetries = 20;
                for (int i = 0; i < maxRetries; ++i) {
                    Throwable pendingThr = null;
                    try {
                        performDexOptSecondaryImpl(pmBinder, transactionCode, packageName, targetCompilerFilter, force);
                    } catch (Throwable thr) {
                        pendingThr = thr;
                    }
                    SystemClock.sleep(3000);
                    if (oatFile.exists()) {
                        break;
                    }
                    if (i == maxRetries - 1) {
                        if (pendingThr != null) {
                            throw pendingThr;
                        }
                        if (!oatFile.exists()) {
                            throw new IllegalStateException("Expected oat file: " + oatFile.getAbsolutePath() + " does not exist.");
                        }
                    }
                }
                ShareTinkerLog.i(TAG, "[+] Secondary dexopt done.");
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable thr) {
                throw new IllegalStateException("Failure on triggering secondary dexopt", thr);
            }
        }

        private static int queryPerformDexOptSecondaryTransactionCode() throws UnsupportedOperationException {
            try {
                final Method getDeclaredFieldMethod = ShareReflectUtil.findMethod(Class.class, "getDeclaredField", String.class);
                getDeclaredFieldMethod.setAccessible(true);
                final Field cstField = (Field) getDeclaredFieldMethod.invoke(Class.forName("android.content.pm.IPackageManager$Stub"),
                      "TRANSACTION_performDexOptSecondary");
                cstField.setAccessible(true);
                return (int) cstField.get(null);
            } catch (Throwable thr) {
                throw new UnsupportedOperationException("Cannot query transaction code of performDexOptSecondary.", thr);
            }
        }

        private static final String PM_INTERFACE_DESCRIPTOR = "android.content.pm.IPackageManager";

        private static void performDexOptSecondaryImpl(IBinder pmBinder, int transactionCode, String packageName, String compileFilter, boolean force) {
            Parcel data = null;
            Parcel reply = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                data = Parcel.obtain();
                reply = Parcel.obtain();
                boolean result;
                data.writeInterfaceToken(PM_INTERFACE_DESCRIPTOR);
                data.writeString(packageName);
                data.writeString(compileFilter);
                data.writeInt(((force) ? (1) : (0)));
                boolean status = false;
                try {
                    status = pmBinder.transact(transactionCode, data, reply, 0);
                    if (!status) {
                        throw new IllegalStateException("Binder transaction failure.");
                    }
                } catch (RemoteException e) {
                    throw new IllegalStateException(e);
                }
                try {
                    reply.readException();
                } catch (Throwable thr) {
                    throw new IllegalStateException(thr);
                }
                result = (0 != reply.readInt());
                if (!result) {
                    ShareTinkerLog.w(TAG, "[!] System API return false.");
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
                if (reply != null) {
                    reply.recycle();
                }
                if (data != null) {
                    data.recycle();
                }
            }
        }

        private static void registerDexModule(Context context, String dexPath, String oatPath) throws IllegalStateException {
            try {
                final PackageManager syncPM = getSynchronizedPackageManager(context);
                final Method registerDexModuleMethod = ShareReflectUtil.findMethod(syncPM.getClass(),
                "registerDexModule", String.class, PackageManager$DexModuleRegisterCallback.class);
                final File oatFile = new File(oatPath);
                final int maxRetries = 2;
                for (int i = 0; i < maxRetries; ++i) {
                    Throwable pendingThr = null;
                    try {
                        registerDexModuleMethod.invoke(syncPM, dexPath, new PackageManager$DexModuleRegisterCallback() {
                            @Override
                            public void onDexModuleRegistered(String dexModulePath, boolean success, String message) {
                                ShareTinkerLog.i(TAG, "[+] onDexModuleRegistered, path: %s, is_success: %s, msg: %s", dexModulePath, success, message);
                            }
                        });
                    } catch (Throwable thr) {
                        pendingThr = thr;
                    }
                    SystemClock.sleep(3000);
                    if (oatFile.exists()) {
                        break;
                    }
                    if (i == maxRetries - 1) {
                        if (pendingThr != null) {
                            throw pendingThr;
                        }
                        if (!oatFile.exists()) {
                            throw new IllegalStateException("Expected oat file: " + oatFile.getAbsolutePath() + " does not exist.");
                        }
                    }
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable thr) {
                throw new IllegalStateException("Fail to call registerDexModule.", thr);
            }
        }

        private static final PackageManager[] CACHED_SYNC_PM  = {null};

        private static PackageManager getSynchronizedPackageManager(Context context) throws Throwable {
            synchronized (CACHED_SYNC_PM) {
                if (CACHED_SYNC_PM[0] != null) {
                    return CACHED_SYNC_PM[0];
                }
                final Class<?> serviceManagerClazz = Class.forName("android.os.ServiceManager");
                final Method getServiceMethod = ShareReflectUtil.findMethod(serviceManagerClazz, "getService", String.class);
                final IBinder pmBinder = (IBinder) getServiceMethod.invoke(null, "package");
                final IBinder syncPMBinder = (IBinder) Proxy.newProxyInstance(context.getClassLoader(), pmBinder.getClass().getInterfaces(), new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("transact".equals(method.getName())) {
                            // FLAG_ONEWAY => NONE.
                            args[3] = 0;
                        }
                        return method.invoke(pmBinder, args);
                    }
                });
                final Class<?> pmStubClazz = Class.forName("android.content.pm.IPackageManager$Stub");
                final Method asInterfaceMethod = ShareReflectUtil.findMethod(pmStubClazz, "asInterface", IBinder.class);
                final IInterface pmItf = (IInterface) asInterfaceMethod.invoke(null, syncPMBinder);
                final Object contextImpl = (context instanceof ContextWrapper ? ((ContextWrapper) context).getBaseContext() : context);
                final Class<?> appPMClazz = Class.forName("android.app.ApplicationPackageManager");
                final Constructor<?> appPMCtor = appPMClazz.getDeclaredConstructor(contextImpl.getClass(), pmItf.getClass().getInterfaces()[0]);
                final PackageManager res = (PackageManager) appPMCtor.newInstance(contextImpl, pmItf);
                CACHED_SYNC_PM[0] = res;
                return res;
            }
        }

        private void interpretDex2Oat(String dexFilePath, String oatFilePath) throws IOException {
            // add process lock for interpret mode
            final File oatFile = new File(oatFilePath);
            if (!oatFile.exists()) {
                oatFile.getParentFile().mkdirs();
            }

            File lockFile = new File(oatFile.getParentFile(), INTERPRET_LOCK_FILE_NAME);
            ShareFileLockHelper fileLock = null;
            try {
                fileLock = ShareFileLockHelper.getFileLock(lockFile);

                final List<String> commandAndParams = new ArrayList<>();
                commandAndParams.add("dex2oat");
                // for 7.1.1, duplicate class fix
                if (Build.VERSION.SDK_INT >= 24) {
                    commandAndParams.add("--runtime-arg");
                    commandAndParams.add("-classpath");
                    commandAndParams.add("--runtime-arg");
                    commandAndParams.add("&");
                }
                commandAndParams.add("--dex-file=" + dexFilePath);
                commandAndParams.add("--oat-file=" + oatFilePath);
                commandAndParams.add("--instruction-set=" + targetISA);
                if (Build.VERSION.SDK_INT > 25) {
                    commandAndParams.add("--compiler-filter=quicken");
                } else {
                    commandAndParams.add("--compiler-filter=interpret-only");
                }

                final ProcessBuilder pb = new ProcessBuilder(commandAndParams);
                pb.redirectErrorStream(true);
                final Process dex2oatProcess = pb.start();
                StreamConsumer.consumeInputStream(dex2oatProcess.getInputStream());
                StreamConsumer.consumeInputStream(dex2oatProcess.getErrorStream());
                try {
                    final int ret = dex2oatProcess.waitFor();
                    if (ret != 0) {
                        throw new IOException("dex2oat works unsuccessfully, exit code: " + ret);
                    }
                } catch (InterruptedException e) {
                    throw new IOException("dex2oat is interrupted, msg: " + e.getMessage(), e);
                }
            } finally {
                try {
                    if (fileLock != null) {
                        fileLock.close();
                    }
                } catch (IOException e) {
                    ShareTinkerLog.w(TAG, "release interpret Lock error", e);
                }
            }
        }
    }

    private static class StreamConsumer {
        static final Executor STREAM_CONSUMER = Executors.newSingleThreadExecutor();

        static void consumeInputStream(final InputStream is) {
            STREAM_CONSUMER.execute(new Runnable() {
                @Override
                public void run() {
                    if (is == null) {
                        return;
                    }
                    final byte[] buffer = new byte[256];
                    try {
                        while ((is.read(buffer)) > 0) {
                            // To satisfy checkstyle rules.
                        }
                    } catch (IOException ignored) {
                        // Ignored.
                    } finally {
                        try {
                            is.close();
                        } catch (Exception ignored) {
                            // Ignored.
                        }
                    }
                }
            });
        }
    }
}
