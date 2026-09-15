package com.alexander.routestory.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.QueryProductDetailsParams
import com.alexander.routestory.data.ProRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Play Billing wrapper for the one-time Pro purchase.
 * Keeps a single client, restores purchases after reinstall and
 * falls back to a demo activation when Play services are not available.
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val proRepository: ProRepository
) {

    companion object {
        const val PRO_PRODUCT_ID = "pro"
    }

    private val _billingAvailable = MutableStateFlow(true)
    val billingAvailable: StateFlow<Boolean> = _billingAvailable.asStateFlow()

    private var client: BillingClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Connects once and reuses the existing client afterwards. */
    fun connect(onResult: (Boolean) -> Unit) {
        val existing = client
        if (existing != null) {
            if (existing.isReady) {
                onResult(true)
            } else {
                existing.startConnection(connectionListener(onResult))
            }
            return
        }
        val billingClient = BillingClient.newBuilder(context)
            .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client = billingClient
        billingClient.startConnection(connectionListener(onResult))
    }

    private fun connectionListener(onResult: (Boolean) -> Unit): BillingClientStateListener =
        object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                _billingAvailable.value = ok
                if (ok) restorePurchases()
                scope.launch { onResult(ok) }
            }

            override fun onBillingServiceDisconnected() {
                _billingAvailable.value = false
            }
        }

    /** Restores a previously purchased Pro, e.g. after reinstall. */
    private fun restorePurchases() {
        val billingClient = client ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            }
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases)
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        scope.launch {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    purchase.products.contains(PRO_PRODUCT_ID)
                ) {
                    val current = client
                    if (!purchase.isAcknowledged && current != null) {
                        val params = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()
                        current.acknowledgePurchase(params) { }
                    }
                    proRepository.setPro(true)
                }
            }
        }
    }

    fun purchase(activity: Activity, onLaunched: (Boolean) -> Unit) {
        val billingClient = client
        if (billingClient == null) {
            onLaunched(false)
            return
        }
        val queryParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRO_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        billingClient.queryProductDetailsAsync(queryParams) { result, detailsList ->
            val details = detailsList?.firstOrNull()
            if (result.responseCode != BillingClient.BillingResponseCode.OK || details == null) {
                onLaunched(false)
                return@queryProductDetailsAsync
            }
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(details)
                            .build()
                    )
                )
                .build()
            val code = billingClient.launchBillingFlow(activity, flowParams).responseCode
            onLaunched(code == BillingClient.BillingResponseCode.OK)
        }
    }

    fun destroy() {
        try { client?.endConnection() } catch (_: Exception) {}
        client = null
    }
}