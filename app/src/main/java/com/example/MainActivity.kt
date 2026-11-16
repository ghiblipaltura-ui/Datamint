package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.repository.DataMintRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.signOutUser
import com.example.ui.screens.HistoryLedgerScreen
import com.example.ui.screens.MarketplaceScreen
import com.example.ui.screens.NodeDashboardScreen
import com.example.ui.screens.WalletScreen
import com.example.ui.theme.DataMintTheme
import com.example.ui.viewmodel.DataMintViewModel
import com.example.ui.viewmodel.UiState
import java.util.Locale
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

const val ROUTE_NODE = "node"
const val ROUTE_MARKET = "market"
const val ROUTE_WALLET = "wallet"
const val ROUTE_LEDGER = "ledger"

private data class NavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val Destinations = listOf(
    NavDestination(ROUTE_NODE, R.string.nav_node, Icons.Rounded.CellTower, Icons.Outlined.CellTower, "nav_node"),
    NavDestination(ROUTE_MARKET, R.string.nav_market, Icons.Rounded.Storefront, Icons.Outlined.Storefront, "nav_market"),
    NavDestination(ROUTE_WALLET, R.string.nav_wallet, Icons.Rounded.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet, "nav_wallet"),
    NavDestination(ROUTE_LEDGER, R.string.nav_history, Icons.Rounded.History, Icons.Outlined.History, "nav_ledger")
)

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DataMintTheme {
                DataMintApp()
            }
        }
    }
}

@Composable
fun DataMintApp(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            MainFeatureShell(
                currentUserId = user.uid,
                displayName = user.displayName ?: user.email?.substringBefore("@") ?: "DataMint Seller"
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainFeatureShell(
    currentUserId: String,
    displayName: String
) {
    val viewModel: DataMintViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                DataMintViewModel(
                    repository = DataMintRepository(db),
                    currentUserId = currentUserId,
                    initialDisplayName = displayName,
                    databaseId = databaseId
                )
            }
        }
    )

    val profileState by viewModel.userProfile.collectAsStateWithLifecycle()
    val sessionsState by viewModel.sessions.collectAsStateWithLifecycle()
    val payoutsState by viewModel.payouts.collectAsStateWithLifecycle()
    val activeListingsState by viewModel.activeListings.collectAsStateWithLifecycle()
    val myListingsState by viewModel.myListings.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val liveUncreditedMb by viewModel.uncreditedMb.collectAsStateWithLifecycle()
    val liveUncreditedInr by viewModel.uncreditedEarningsInr.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(actionMessage) {
        actionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_NODE

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val navigateTo: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    actions = {
                        val headerProfile = (profileState as? UiState.Success)?.data
                        val headerBalance = (headerProfile?.availableBalanceInr ?: 0.0) + liveUncreditedInr
                        val headerBalanceText = if (headerBalance > 0.0 && headerBalance < 10.0) {
                            String.format(Locale.US, "₹%.4f", headerBalance)
                        } else {
                            String.format(Locale.US, "₹%.2f", headerBalance)
                        }
                        Surface(
                            onClick = { navigateTo(ROUTE_WALLET) },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .testTag("topbar_live_balance_pill")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AccountBalanceWallet,
                                    contentDescription = "Live wallet balance",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = headerBalanceText,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                signOutUser(credentialManager, scope)
                            },
                            modifier = Modifier.testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Logout,
                                contentDescription = stringResource(R.string.sign_out)
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            val label = stringResource(dest.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            val label = stringResource(dest.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_NODE
                    ) {
                        composable(ROUTE_NODE) {
                            NodeDashboardScreen(
                                profileState = profileState,
                                liveUncreditedMb = liveUncreditedMb,
                                liveUncreditedInr = liveUncreditedInr,
                                isSubmitting = isSubmitting,
                                onSaveSettings = viewModel::saveNodeSettings,
                                onSessionFinished = viewModel::recordCompletedSession
                            )
                        }
                        composable(ROUTE_MARKET) {
                            MarketplaceScreen(
                                currentUserId = currentUserId,
                                sellerAlias = displayName,
                                activeListingsState = activeListingsState,
                                myListingsState = myListingsState,
                                isSubmitting = isSubmitting,
                                onCreateListing = viewModel::createListing,
                                onUpdateStatus = viewModel::updateListingStatus,
                                onDeleteListing = viewModel::deleteListing
                            )
                        }
                        composable(ROUTE_WALLET) {
                            WalletScreen(
                                profileState = profileState,
                                payoutsState = payoutsState,
                                liveUncreditedInr = liveUncreditedInr,
                                isSubmitting = isSubmitting,
                                onRequestPayout = viewModel::requestPayout,
                                onCancelPayout = viewModel::cancelPayout
                            )
                        }
                        composable(ROUTE_LEDGER) {
                            HistoryLedgerScreen(sessionsState = sessionsState)
                        }
                    }
                }
            }
        }
    }
}
