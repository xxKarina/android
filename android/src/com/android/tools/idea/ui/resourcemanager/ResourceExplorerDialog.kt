/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.ui.resourcemanager

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceItem
import com.android.resources.ResourceType
import com.android.tools.adtui.common.AdtUiUtils
import com.android.tools.idea.editors.theme.ResolutionUtils
import com.android.tools.idea.ui.resourcecommon.ResourcePickerDialog
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import org.jetbrains.android.dom.resources.ResourceValue
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.annotations.TestOnly
import javax.swing.BorderFactory

/**
 * A [ResourceExplorer] used in a dialog for resource picking.
 *
 * @param facet The current [AndroidFacet], used to determine the module in the ResourceExplorer.
 * @param initialResourceUrl The resourceUrl (@string/name) of the initial value, if any.
 * @param supportedTypes The supported [ResourceType]s that can be picked.
 * @param preferredType The preferred [ResourceType] to show when the [ResourceExplorerDialog] opens, this may be ignored if there's a
 * valid initialResourceUrl given. Eg: "@drawable/foo" will open the dialog in [ResourceType.DRAWABLE].
 * @param showSampleData Includes [ResourceType.SAMPLE_DATA] resources as options to pick.
 * @param currentFile The [VirtualFile] that may have an specific preview configuration.
 */
class ResourceExplorerDialog(
  facet: AndroidFacet,
  initialResourceUrl: String?,
  supportedTypes: Set<ResourceType>,
  preferredType: ResourceType?,
  showSampleData: Boolean,
  currentFile: VirtualFile?
): ResourcePickerDialog(facet.module.project) {

  @TestOnly // TODO: consider getting this for tests in a better way.
  val resourceExplorerPanel = kotlin.run {
    // Get the resource name and type from the given resource url and try to select it in the ResourceExplorer.
    // Eg: From '@android:color/color_primary' we select 'color_primary' under 'Color' resources.
    val resourceValue = initialResourceUrl?.let {
      ResourceValue.reference(initialResourceUrl)?.takeIf { it.resourceName != null && it.type != null }
    }
    val resourceType = resourceValue?.type?.takeIf { supportedTypes.contains(it) } ?: preferredType
    return@run ResourceExplorer.createResourcePicker(facet,
                                                     getSortedResourceTypes(supportedTypes),
                                                     resourceValue?.resourceName,
                                                     resourceType,
                                                     showSampleData,
                                                     currentFile,
                                                     this::updateSelectedResource,
                                                     this::doSelectResource)
  }

  private var pickedResourceName: String? = null

  init {
    ResourceManagerTracking.logDialogOpens()
    init()
    doValidate()
  }

  override fun createCenterPanel() = resourceExplorerPanel.apply {
    border = BorderFactory.createMatteBorder(0, 0, JBUI.scale(1), 0, AdtUiUtils.DEFAULT_BORDER_COLOR)
  }

  override fun dispose() {
    super.dispose()
    Disposer.dispose(resourceExplorerPanel)
  }

  override val resourceName: String?
    get() = pickedResourceName

  private fun updateSelectedResource(resource: ResourceItem) {
    pickedResourceName = resource.getReferenceString()
  }

  private fun doSelectResource(resource: ResourceItem) {
    updateSelectedResource(resource)
    doOKAction()
  }
}

/**
 * Returns a sorted array of the given [originalSet] so that the order matches the tabs in the Resource Manager.
 *
 * Any [ResourceType] not supported in the Resource Manager is just added to the end of the array.
 */
private fun getSortedResourceTypes(originalSet: Set<ResourceType>): Array<ResourceType> {
  val resManagerTypes = MANAGER_SUPPORTED_RESOURCES
  val sortedTypes = ArrayList<ResourceType>()
  val remainingTypes = mutableSetOf(*originalSet.toTypedArray())

  resManagerTypes.forEach { resourceType ->
    if (remainingTypes.remove(resourceType)) {
      sortedTypes.add(resourceType)
    }
  }
  sortedTypes.addAll(remainingTypes)
  return sortedTypes.toTypedArray()
}

/** The resource reference in the form of @namespace:color/color_name or ?namespace:attr/attr_name. */
private fun ResourceItem.getReferenceString(): String {
  val resourceReference = referenceToSelf
  var qualifiedName = resourceReference.qualifiedName
  if (resourceReference.namespace == ResourceNamespace.TOOLS && qualifiedName.lastIndexOf(":") < 0) {
    // TODO: Fix. This is a workaround, qualified name should already return this.
    qualifiedName = resourceReference.namespace.toString() + ":" + qualifiedName
  }
  return ResolutionUtils.getResourceUrlFromQualifiedName(qualifiedName, type.getName())
}
