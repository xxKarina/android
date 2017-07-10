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
package android.support.annotation;

@interface UiThread {}
@interface WorkerThread {}

@FunctionalInterface
public interface Runnable {
  public abstract void run();
}

class ThreadAnnotations {
  @UiThread static void uiThreadStatic() { unannotatedStatic(); }
  static void unannotatedStatic() { workerThreadStatic(); }
  @WorkerThread static void workerThreadStatic() {}

  @UiThread void uiThread() { unannotated(); }
  void unannotated() { workerThread(); }
  @WorkerThread void workerThread() {}

  @UiThread void runUi() {}
  void runIt(Runnable r) { r.run(); }
  @WorkerThread void callRunIt() {
    runIt(() -> runUi());
  }

  public static void main(String[] args) {
    ThreadAnnotations instance = new ThreadAnnotations();
    instance.uiThread();
  }
}