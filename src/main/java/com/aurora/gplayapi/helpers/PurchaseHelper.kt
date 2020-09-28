package com.aurora.gplayapi.helpers

import com.aurora.gplayapi.BuyResponse
import com.aurora.gplayapi.Constants.PATCH_FORMAT
import com.aurora.gplayapi.DeliveryResponse
import com.aurora.gplayapi.GooglePlayApi
import com.aurora.gplayapi.ResponseWrapper
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.File
import com.aurora.gplayapi.data.providers.HeaderProvider
import com.aurora.gplayapi.network.HttpClient
import java.io.IOException
import java.util.*

class PurchaseHelper(authData: AuthData) : BaseHelper(authData) {

    @Throws(Exception::class)
    fun getBuyResponse(packageName: String, versionCode: Int, offerType: Int): BuyResponse? {
        val params: MutableMap<String, String> = HashMap()
        params["ot"] = offerType.toString()
        params["doc"] = packageName
        params["vc"] = versionCode.toString()
        val responseBody = HttpClient.post(GooglePlayApi.PURCHASE_URL, HeaderProvider.getDefaultHeaders(authData), params)
        val payload = getPayLoadFromBytes(responseBody?.bytes())
        return if (payload != null && payload.hasBuyResponse()) payload.buyResponse else null
    }

    @Throws(IOException::class)
    fun getDeliveryResponse(packageName: String,
                            installedVersionCode: Int = 0,
                            updateVersionCode: Int,
                            offerType: Int,
                            patchFormats: Array<PATCH_FORMAT?> = arrayOf(PATCH_FORMAT.GDIFF, PATCH_FORMAT.GZIPPED_GDIFF, PATCH_FORMAT.GZIPPED_BSDIFF),
                            downloadToken: String?): DeliveryResponse? {

        val params: MutableMap<String, String> = HashMap()
        params["ot"] = offerType.toString()
        params["doc"] = packageName
        params["vc"] = updateVersionCode.toString()

        /*if (installedVersionCode > 0) {
            params.put("bvc", String.valueOf(installedVersionCode));
            params.put("pf", String.valueOf(patchFormats[0].value));
        }*/
        if (null != downloadToken && downloadToken.isNotEmpty()) {
            params["dtok"] = downloadToken
        }
        val responseBody = HttpClient[GooglePlayApi.DELIVERY_URL, HeaderProvider.getDefaultHeaders(authData), params]
        val payload = ResponseWrapper.parseFrom(responseBody?.bytes()).payload
        return if (payload != null && payload.hasDeliveryResponse()) payload.deliveryResponse else null
    }

    @Throws(Exception::class)
    fun purchase(packageName: String, versionCode: Int, offerType: Int): List<File> {
        val buyResponse = getBuyResponse(packageName, versionCode, offerType)
        val downloadToken = buyResponse!!.downloadToken
        val deliveryResponse = getDeliveryResponse(packageName = packageName,
                updateVersionCode = versionCode,
                offerType = offerType,
                downloadToken = downloadToken)
        return getUrlsFromDeliveryResponse(packageName, deliveryResponse)
    }

    private fun getUrlsFromDeliveryResponse(packageName: String?, deliveryResponse: DeliveryResponse?): List<File> {
        val fileList: MutableList<File> = ArrayList()
        if (deliveryResponse != null) {
            //Add base apk
            val androidAppDeliveryData = deliveryResponse.appDeliveryData
            if (androidAppDeliveryData != null) {
                fileList.add(File(
                        name = String.format("%s.apk", packageName),
                        url = androidAppDeliveryData.downloadUrl,
                        size = androidAppDeliveryData.downloadSize,
                        type = File.FileType.BASE
                ))

                //Obb & patches (if any)
                val fileMetadataList = deliveryResponse.appDeliveryData.additionalFileList
                if (fileMetadataList != null) {
                    for (appFileMetadata in fileMetadataList) {
                        val isOBB = appFileMetadata.fileType == 0
                        fileList.add(File(
                                name = String.format("%s.%s.%s", if (isOBB) "main" else "patch", packageName, "obb"),
                                url = appFileMetadata.downloadUrl,
                                size = appFileMetadata.size,
                                type = if (appFileMetadata.fileType == 0) File.FileType.OBB else File.FileType.PATCH
                        ))
                    }
                }

                //Add split apks (if any)
                val splitDeliveryDataList = deliveryResponse.appDeliveryData.splitDeliveryDataList
                if (fileMetadataList != null) {
                    for (splitDeliveryData in splitDeliveryDataList) {
                        fileList.add(File(
                                name = splitDeliveryData.name,
                                url = splitDeliveryData.downloadUrl,
                                size = splitDeliveryData.downloadSize,
                                type = File.FileType.SPLIT
                        ))
                    }
                }
            }
        }
        return fileList
    }
}