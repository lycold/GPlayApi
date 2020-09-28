package com.aurora.gplayapi.helpers

import com.aurora.gplayapi.*
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.SearchBundle
import com.aurora.gplayapi.data.models.SearchBundle.SubBundle
import com.aurora.gplayapi.data.providers.HeaderProvider.getDefaultHeaders
import com.aurora.gplayapi.network.HttpClient
import okhttp3.ResponseBody
import java.util.*

class SearchHelper(authData: AuthData) : BaseHelper(authData) {
    private val bundleSet: MutableSet<SubBundle> = HashSet()
    private var query: String = String()

    @Throws(Exception::class)
    fun searchSuggestions(query: String): List<SearchSuggestEntry> {
        val header: MutableMap<String, String> = getDefaultHeaders(authData)
        val paramString = String.format("?q=%s&sb=%d&sst=%d&sst=%d",
                query,
                5,
                Constants.SEARCH_SUGGESTION_TYPE.SEARCH_STRING.value,
                Constants.SEARCH_SUGGESTION_TYPE.APP.value)
        val responseBody = HttpClient.getX(GooglePlayApi.URL_SEARCH_SUGGEST, header, paramString)
        val searchSuggestResponse = getSearchSuggestResponseFromBytes(responseBody!!.bytes())
        return if (searchSuggestResponse != null && searchSuggestResponse.entryCount > 0) {
            searchSuggestResponse.entryList
        } else ArrayList()
    }

    @Throws(Exception::class)
    fun searchResults(query: String, nextPageUrl: String?): List<App> {
        this.query = query
        val header: MutableMap<String, String> = getDefaultHeaders(authData)
        val param: MutableMap<String, String> = HashMap()
        param["q"] = query
        param["c"] = "3"
        param["ksm"] = "1"

        val responseBody: ResponseBody?
        responseBody = if (nextPageUrl!!.isNotBlank()) {
            HttpClient[GooglePlayApi.URL_SEARCH + "/" + nextPageUrl, header]
        } else {
            HttpClient[GooglePlayApi.URL_SEARCH, header, param]
        }
        val payload = getPrefetchPayLoad(responseBody?.bytes())
        if (payload.hasListResponse()) {
            val searchBundle = getSearchBundle(payload.listResponse)
            bundleSet.addAll(searchBundle.subBundles)
            return searchBundle.appList
        }
        return ArrayList()
    }

    @Throws(Exception::class)
    operator fun next(): List<App> {
        val appList: MutableList<App> = ArrayList()
        val iterator: Iterator<SubBundle> = bundleSet.iterator()
        val subBundle = iterator.next()
        val nextPageUrl = subBundle.nextPageUrl
        //Process only generic type
        if (nextPageUrl.isNotEmpty() && subBundle.type === SearchBundle.Type.GENERIC) {
            appList.addAll(searchResults(query, nextPageUrl))
        }
        //Remove currentNextPageUrl
        bundleSet.remove(subBundle)
        return appList
    }

    operator fun hasNext(): Boolean {
        return bundleSet.size > 0
    }

    private fun getSearchBundle(listResponse: ListResponse): SearchBundle {
        val searchBundle = SearchBundle()
        val appList: MutableList<App> = ArrayList()
        val itemList = listResponse.itemList
        for (item in itemList) {
            if (item.title.isEmpty()) {
                if (item.subItemCount > 0) {
                    for (subItem in item.subItemList) {
                        //Sublist with non-empty title are useless data (Similar apps, Apps you may also like)
                        if (subItem.title.isEmpty()) {
                            //Filter out only apps, discard other items (Music, Ebooks, Movies)
                            if (subItem.type == 45) {
                                appList.addAll(getAppsFromItem(subItem))
                            }
                        }
                        searchBundle.subBundles.add(getSubBundle(subItem))
                    }
                    searchBundle.subBundles.add(getSubBundle(item))
                }
            }
        }
        searchBundle.appList = appList
        return searchBundle
    }

    companion object {
        private const val SEARCH_TYPE_EXTRA = "_-"
        private fun getSubBundle(item: Item): SubBundle {
            var nextPageUrl = String()
            try {
                nextPageUrl = item.containerMetadata.nextPageUrl
                if (nextPageUrl.isNotBlank()) {
                    if (nextPageUrl.contains(SEARCH_TYPE_EXTRA)) {
                        if (nextPageUrl.startsWith("getCluster?enpt=CkC"))
                            return SubBundle(nextPageUrl, SearchBundle.Type.SIMILAR)
                        if (nextPageUrl.startsWith("getCluster?enpt=CkG"))
                            return SubBundle(nextPageUrl, SearchBundle.Type.RELATED_TO_YOUR_SEARCH)
                    } else {
                        return SubBundle(nextPageUrl, SearchBundle.Type.GENERIC)
                    }
                }
            } catch (ignored: Exception) {
            }
            return SubBundle(nextPageUrl, SearchBundle.Type.BOGUS)
        }
    }
}