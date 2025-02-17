/*
 * Copyright 2020-2022 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.converter.conversion

import com.exactpro.th2.converter.controllers.ConversionSummary
import com.exactpro.th2.converter.controllers.ErrorMessage
import com.exactpro.th2.converter.`fun`.ConvertibleBoxSpecV2
import com.exactpro.th2.converter.model.Th2Resource
import com.exactpro.th2.converter.util.Mapper.YAML_MAPPER
import com.exactpro.th2.infrarepo.repo.RepositoryResource
import com.exactpro.th2.model.latest.box.Spec
import com.exactpro.th2.model.latest.link.LinkEndpoint
import com.exactpro.th2.model.v1.link.LinkSpecV1
import com.exactpro.th2.model.v1.link.MultiDictionary
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.regex.Pattern

class LinksInserter {
    companion object {
        private const val DICTIONARIES_ALIAS = "dictionaries"
    }

    private val errors = ArrayList<ErrorMessage>()

    private fun generateResourceToLinkMap(
        links: Set<RepositoryResource>
    ): MutableMap<String, MutableMap<String, LinkTo>> {
        val resourceMap: MutableMap<String, MutableMap<String, LinkTo>> = HashMap()
        for (link in links) {
            val spec: LinkSpecV1
            try {
                spec = YAML_MAPPER.convertValue(link.spec)
            } catch (e: Exception) {
                val linkName = link.metadata.name
                errors.add(
                    ErrorMessage(
                        linkName,
                        "Spec of your links resource may not match the expected ${link.version} version. " +
                            "For example, inappropriate field(s) may be present or required values missing"
                    )
                )
                errors.add(ErrorMessage(linkName, e.message))
                continue
            }
            val mqLinks = spec.boxesRelation?.routerMq
            val grpcLinks = spec.boxesRelation?.routerGrpc
            val dictionaryLinks = spec.dictionariesRelation
            val multiDictionaryLinks = spec.multiDictionariesRelation

            mqLinks?.forEach { (_, from, to) ->
                resourceMap
                    .getOrPut(to.box!!) { HashMap() }
                    .getOrPut(to.pin!!) { LinkTo() }
                    .mq.add(
                        LinkEndpoint(from.box, from.pin)
                    )
            }

            grpcLinks?.forEach { (_, from, to) ->
                resourceMap
                    .getOrPut(from.box!!) { HashMap() }
                    .getOrPut(from.pin!!) { LinkTo() }
                    .grpc.add(
                        LinkEndpoint(to.box, to.pin)
                    )
            }

            dictionaryLinks?.forEach { (_, box, dictionary) ->
                run {
                    val dictionariesForSpecificBox = resourceMap
                        .getOrPut(box) { HashMap() }
                        .getOrPut(DICTIONARIES_ALIAS) { LinkTo() }
                        .dictionaries
                    val type = dictionary.type
                    if (dictionariesForSpecificBox.containsKey(type)) {
                        errors.add(ErrorMessage(box, "Multiple dictionaries linked under type: $type"))
                    } else {
                        dictionariesForSpecificBox[type] = "\${dictionary_link:${dictionary.name}}"
                    }
                }
            }

            multiDictionaryLinks?.forEach { (_, box, dictionaries) ->
                resourceMap
                    .getOrPut(box) { HashMap() }
                    .getOrPut(DICTIONARIES_ALIAS) { LinkTo() }
                    .multipleDictionary.addAll(dictionaries)
            }
        }
        return resourceMap
    }

    data class LinkTo(
        val mq: MutableList<LinkEndpoint> = ArrayList(),
        val grpc: MutableList<LinkEndpoint> = ArrayList(),
        val dictionaries: MutableMap<String, String> = HashMap(),
        val multipleDictionary: MutableList<MultiDictionary> = ArrayList()
    )

    fun addErrorsToSummary(summary: ConversionSummary) {
        summary.errorMessages.addAll(errors)
    }

    fun insertLinksIntoBoxes(convertedResources: List<Th2Resource>, links: Set<RepositoryResource>) {
        val resToLinkMap = generateResourceToLinkMap(links)
        val convertedResourcesMap = convertedResources.associateBy { it.metadata.name }
        resToLinkMap.forEach { (key, value) ->
            if (convertedResourcesMap.containsKey(key)) {
                val resource = convertedResourcesMap[key]
                val spec: Spec = YAML_MAPPER.convertValue(resource!!.spec)
                val mqPinMap = spec.pins?.mq?.subscribers?.associateBy { it.name }
                val grpcPinMap = spec.pins?.grpc?.client?.associateBy { it.name }
                value.forEach { (key, value) ->
                    mqPinMap?.get(key)?.linkTo = value.mq
                    grpcPinMap?.get(key)?.linkTo = value.grpc
                }
                val multiDictionaries = value[DICTIONARIES_ALIAS]?.multipleDictionary
                if (multiDictionaries?.isNotEmpty() == true) {
                    insertDictionariesAsAliases(spec, multiDictionaries)
                }
                val dictionaries = value[DICTIONARIES_ALIAS]?.dictionaries
                if (dictionaries?.isNotEmpty() == true) {
                    spec.customConfig = spec.customConfig ?: mutableMapOf()
                    spec.customConfig?.put(DICTIONARIES_ALIAS, dictionaries)
                }

                resource.specWrapper = ConvertibleBoxSpecV2(spec)
            }
        }
    }

    private fun insertDictionariesAsAliases(spec: Spec, multiDictionaries: MutableList<MultiDictionary>) {
        val customConfigStr = YAML_MAPPER.writeValueAsString(spec.customConfig)
        val patternStr: StringBuilder = StringBuilder()
        val dictionary: MutableMap<String, String> = HashMap()
        multiDictionaries.forEach {
            patternStr.append(it.alias + "\\n| ")
            dictionary[" ${it.alias}\n"] = " \${dictionary_link:${it.name}}\n"
        }
        val pattern = Pattern.compile("( ${patternStr.dropLast(2)})")
        val matcher = pattern.matcher(customConfigStr)
        val stringBuffer = StringBuffer()
        while (matcher.find()) {
            val replacement = dictionary[matcher.group(0)]
            matcher.appendReplacement(stringBuffer, "")
            stringBuffer.append(replacement)
        }
        matcher.appendTail(stringBuffer)
        val customConfig: MutableMap<String, Any>? = YAML_MAPPER.readValue(stringBuffer.toString())
        spec.customConfig = customConfig
    }
}
