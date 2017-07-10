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
package com.android.tools.idea.experimental.callgraph

import com.intellij.analysis.AnalysisScope
import com.intellij.analysis.BaseAnalysisAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/** Builds a call graph and prints a description, optionally to a dot file. */
class CallGraphAction : BaseAnalysisAction("Call Graph", "Call Graph") {
  private val LOG = Logger.getInstance(CallGraphAction::class.java)

  override fun analyze(project: Project, scope: AnalysisScope) {
    val files = buildUFiles(project, scope)
    val nonContextualReceiverEval = buildIntraproceduralReceiverEval(files)
    val callGraph = buildCallGraph(files, nonContextualReceiverEval)
    LOG.info(callGraph.toString())
    LOG.info(callGraph.dump())
  }
}