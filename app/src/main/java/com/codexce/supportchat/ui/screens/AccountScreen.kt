@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import com.codexce.supportchat.data.OwnerPhoto
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.GroupGap
import com.codexce.supportchat.ui.components.OwnerAvatar
import com.codexce.supportchat.ui.components.PlainRow
import com.codexce.supportchat.ui.components.safeClickable
import com.codexce.supportchat.ui.components.debounced
import com.codexce.supportchat.ui.theme.GroupedCardShape
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexce.supportchat.R
import com.codexce.supportchat.data.TenantSession
import com.codexce.supportchat.data.api.SupportApi
import com.codexce.supportchat.data.api.TenantMe
import com.codexce.supportchat.data.api.Website
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.ErrorBanner
import com.codexce.supportchat.ui.components.PersonAvatar
import com.codexce.supportchat.ui.components.ProfileSkeleton
import com.codexce.supportchat.ui.components.StatusPill
import com.codexce.supportchat.ui.components.SupportCard
import com.codexce.supportchat.viewmodel.AuthViewModel
import com.codexce.supportchat.ui.theme.supportButtonColors

/**
 * Account details and account switching.
 *
 * The workspace identity now comes from GET /v1/tenants/me rather than from a database node the
 * app reads directly, because the app no longer reads Firestore at all. What is shown here is
 * exactly what the backend says the verified token is entitled to: tenant, plan, status.
 */
@Composable
fun AccountScreen(
    authViewModel: AuthViewModel,
    onBack: () -> Unit,
) {
    val auth by authViewModel.state.collectAsStateWithLifecycle()
    val tenant by TenantSession.tenant.collectAsStateWithLifecycle()
    val tenantError by TenantSession.lastError.collectAsStateWithLifecycle()
    // Activity context: Credential Manager draws its sheet over the current Activity.
    val context = LocalContext.current

    /*
     * Edit state lives up here, not in SignedInSection, because the control that drives it is
     * the pencil in the top bar and the bar is owned by this Scaffold. Hoisting the drafts as
     * well keeps one source of truth: the bar decides when a save happens, the section only
     * renders whatever the drafts currently hold.
     */
    val scope = rememberCoroutineScope()
    var editMode by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var ownerDraft by remember { mutableStateOf("") }
    var companyDraft by remember { mutableStateOf("") }
    var phoneDraft by remember { mutableStateOf("") }

    // Opening this screen is the natural moment to re-read the plan: it is the one place that
    // shows it in full, and a coupon applied on another device should be visible here.
    LaunchedEffect(auth.uid) {
        val uid = auth.uid
        if (uid != null) TenantSession.refresh(context, uid)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text("Account", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    /*
                     * One pencil for all three editable fields, which is why the dialogs are
                     * gone. Three separate dialogs meant three round trips to change a name and
                     * a phone number, and nothing on the page said the three belonged together.
                     *
                     * Owner only. The Firestore rules refuse anyone else's write, so showing the
                     * pencil to a member would be an affordance that always ends in an error.
                     */
                    if (auth.uid != null && tenant?.isOwner == true) {
                        androidx.compose.material3.IconButton(
                            enabled = !saving,
                            onClick = debounced {
                                if (editMode) {
                                    saving = true
                                    scope.launch {
                                        runCatching {
                                            SupportApi.updateTenantProfile(
                                                ownerName = ownerDraft.trim(),
                                                companyName = companyDraft.trim(),
                                                phone = phoneDraft.trim(),
                                            )
                                        }
                                            .onSuccess { editMode = false }
                                            .onFailure {
                                                saveError = "Could not save. Try again."
                                            }
                                        saving = false
                                    }
                                } else {
                                    // Seed the drafts at the moment of entering edit mode, not on
                                    // every recomposition, or typing would be overwritten by the
                                    // stored value on the next tenant emission.
                                    ownerDraft = tenant?.ownerName.orEmpty()
                                    companyDraft = tenant?.companyName.orEmpty()
                                    phoneDraft = tenant?.phone.orEmpty()
                                    editMode = true
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (editMode) AppIcons.Check else AppIcons.Pencil,
                                ),
                                contentDescription = if (editMode) "Save" else "Edit profile",
                                modifier = Modifier.size(20.dp),
                                tint = if (editMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState()),
        ) {
            auth.error?.let { message ->
                ErrorBanner(message = message, onDismiss = authViewModel::clearError)
            }

            val uid = auth.uid
            if (uid == null) {
                SignInCard(
                    submitting = auth.submitting,
                    onSubmit = authViewModel::signIn,
                    onGoogle = {
                        authViewModel.signInWithGoogle(
                            context = context,
                            serverClientId = context.getString(R.string.google_web_client_id),
                        )
                    },
                )
            } else {
                SignedInSection(
                    email = auth.email,
                    photoUrl = auth.photoUrl,
                    tenant = tenant,
                    loadError = tenantError,
                    editMode = editMode,
                    ownerDraft = ownerDraft,
                    companyDraft = companyDraft,
                    phoneDraft = phoneDraft,
                    onOwnerChange = { ownerDraft = it },
                    onCompanyChange = { companyDraft = it },
                    onPhoneChange = { phoneDraft = it },
                    saveError = saveError,
                    onDismissSaveError = { saveError = null },
                )
            }
        }
    }
}

@Composable
private fun SignInCard(
    submitting: Boolean,
    onSubmit: (String, String) -> Unit,
    onGoogle: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = "Sign in",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.padding(top = 6.dp))
        Text(
            text = "Messages only arrive while signed in. Your workspace is identified by the " +
                "claims on your account, so there is nothing to type in here twice.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(top = 20.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Email") },
            singleLine = true,
            enabled = !submitting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            enabled = !submitting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.padding(top = 20.dp))
        Button(
            colors = supportButtonColors(),
            onClick = debounced { onSubmit(email, password) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !submitting && email.isNotBlank() && password.isNotBlank(),
        ) {
            if (submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Sign in")
            }
        }

        Spacer(Modifier.padding(top = 20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HorizontalDivider(Modifier.weight(1f))
            Text(
                text = "or",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.weight(1f))
        }
        Spacer(Modifier.padding(top = 20.dp))

        OutlinedButton(
            onClick = debounced(onGoogle),
            modifier = Modifier.fillMaxWidth(),
            enabled = !submitting,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("Continue with Google")
        }

        Spacer(Modifier.padding(top = 12.dp))
        Text(
            text = "The first person to sign in becomes the owner and the workspace is created " +
                "for them automatically. An installation has one owner and no other accounts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SignedInSection(
    email: String,
    photoUrl: String?,
    tenant: TenantMe?,
    loadError: String?,
    editMode: Boolean,
    ownerDraft: String,
    companyDraft: String,
    phoneDraft: String,
    onOwnerChange: (String) -> Unit,
    onCompanyChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    saveError: String?,
    onDismissSaveError: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Starts mirroring the stored photo. Idempotent per tenant, so re-running this on every
    // recomposition of the id cannot stack listeners.
    LaunchedEffect(tenant?.tenantId) {
        tenant?.tenantId?.let { OwnerPhoto.observe(it) }
    }

    /*
     * The linked sites, fetched here rather than read off the tenant claims.
     *
     * TenantMe.website is a single string on the tenant document and it is only written at
     * bootstrap, so a site linked afterwards never appeared on this page - which is exactly the
     * "it does not show when the website is linked" report. The websites subcollection is the
     * real record, and it is what the Link your website screen reads too, so the two screens now
     * agree. A failure leaves the list empty and the row says nothing is linked, which is the
     * same thing this page said before and is honest enough for a read that has no retry.
     */
    var sites by remember { mutableStateOf<List<Website>>(emptyList()) }
    LaunchedEffect(tenant?.tenantId) {
        if (tenant?.tenantId != null) {
            runCatching { SupportApi.websites() }.onSuccess { sites = it }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val id = tenant?.tenantId
        if (uri != null && id != null) {
            uploading = true
            scope.launch {
                photoError = OwnerPhoto.upload(context, id, uri)
                uploading = false
            }
        }
    }

    Column(Modifier.padding(bottom = 32.dp)) {
        photoError?.let { message ->
            ErrorBanner(message = message, onDismiss = { photoError = null })
        }
        saveError?.let { message ->
            ErrorBanner(message = message, onDismiss = onDismissSaveError)
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (tenant == null) {
                ProfileSkeleton()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The one avatar rule, everywhere: Google photo, then the first letter
                    // of the name, then the first letter of the email. This screen used to
                    // slice the name itself and had no email fallback, so an account with a
                    // blank workspace name showed a bare question mark.
                    /*
                     * Tapping the avatar replaces it, but only for the owner.
                     *
                     * The Google photo is still the default and still what everyone sees
                     * until someone deliberately overrides it. A member of the workspace who
                     * is not the owner gets the same avatar with no camera badge and no
                     * gesture, because the stored photo represents the workspace owner and
                     * the database rules will refuse their write anyway - better to not
                     * offer the affordance than to offer it and fail.
                     *
                     * PickVisualMedia rather than GetContent: it opens the system photo
                     * picker, which needs no storage permission at all and hands back exactly
                     * one image. GetContent would have meant READ_MEDIA_IMAGES and a runtime
                     * prompt for a feature that touches one file.
                     */
                    Box(contentAlignment = Alignment.BottomEnd) {
                        OwnerAvatar(
                            name = tenant.name,
                            email = email,
                            photoUrl = photoUrl,
                            seed = email,
                            size = 64.dp,
                            modifier = if (tenant.isOwner) {
                                Modifier.clip(CircleShape).safeClickable(
                                    enabled = !uploading,
                                    onClick = debounced {
                                        picker.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts
                                                    .PickVisualMedia.ImageOnly,
                                            ),
                                        )
                                    },
                                )
                            } else {
                                Modifier
                            },
                        )
                        if (tenant.isOwner) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(AppIcons.ImageSolid),
                                    contentDescription = "Change photo",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(
                            text = tenant.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.padding(top = 6.dp))
                        StatusPill(
                            text = tenant.plan.name + " \u00b7 " + tenant.statusLabel,
                            emphasised = tenant.subscriptionActive,
                        )
                    }
                }
            }
        }

        GroupGap()
        Column(
            // 12dp to match SettingsGroup exactly: the card insets 12 and each row
            // insets 20, so these rows line up with every row on the Settings page.
            Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /*
             * Settings-page styling, with no icon tiles.
             *
             * This is a list of facts about one account, not a menu of destinations. Giving
             * each fact a coloured square would invent ten categories that do not exist, and
             * there is no glyph that means "billing status". Label left, value right, hairline
             * between - the same card shape and the same 20dp row inset as Settings, so the
             * two screens read as one app.
             *
             * Workspace ID and User ID stay removed: internal identifiers the owner can do
             * nothing with.
             */
            /*
             * The three editable facts, together, at the top.
             *
             * They used to sit scattered down the fact list, each behind its own dialog. Reading
             * order now matches intent: the things you can change are the first card, everything
             * below is read-only truth from the backend.
             */
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(GroupedCardShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                if (editMode) {
                    EditField("Owner name", ownerDraft, KeyboardType.Text, onOwnerChange)
                    Hairline()
                    EditField("Company name", companyDraft, KeyboardType.Text, onCompanyChange)
                    Hairline()
                    EditField("Phone number", phoneDraft, KeyboardType.Phone, onPhoneChange)
                } else {
                    PlainRow(
                        title = "Owner",
                        value = tenant?.ownerName?.ifBlank { "Not set" } ?: "\u2026",
                    )
                    Hairline()
                    PlainRow(
                        title = "Company",
                        value = tenant?.companyName?.ifBlank { "Not set" } ?: "\u2026",
                    )
                    Hairline()
                    PlainRow(
                        title = "Phone",
                        value = tenant?.phone?.ifBlank { "Not set" } ?: "\u2026",
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(GroupedCardShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                PlainRow(
                    title = "Role",
                    value = tenant?.role?.replaceFirstChar { it.uppercase() } ?: "\u2026",
                )

                Hairline()
                PlainRow(title = "Email", value = email)

                Hairline()
                if (sites.isEmpty()) {
                    PlainRow(title = "Website", value = "Not linked yet")
                } else {
                    sites.forEachIndexed { index, site ->
                        if (index > 0) Hairline()
                        /*
                         * Fixed label, variable value - the same grammar as every other row in
                         * this card. This used to print site.domain as the TITLE, so whatever
                         * the backend held in that field became the label: a one-character
                         * domain rendered as a row called "g".
                         */
                        PlainRow(
                            title = if (sites.size > 1) "Website " + (index + 1) else "Website",
                            value = site.domain.ifBlank { "Not set" } +
                                if (site.active) " (Live)" else " (Offline)",
                        )
                    }
                }

                Hairline()
                PlainRow(
                    title = "Plan",
                    value = tenant?.let { it.plan.name + " (tier " + it.plan.tier + ")" }
                        ?: "\u2026",
                )
                Hairline()
                PlainRow(title = "Billing status", value = tenant?.statusLabel ?: "\u2026")
                Hairline()
                /*
                 * The renewal date is the only billing history that exists. Nothing records
                 * past payments anywhere, so a "history" list would be an empty box pretending
                 * to be a feature.
                 */
                /*
                 * Keyed on the timestamp, so the formatter is built and run once per renewal
                 * date rather than once per recomposition.
                 *
                 * This screen recomposes on every tick of three separate collected flows, and
                 * constructing a SimpleDateFormat is not cheap - it parses the pattern and
                 * pulls locale data on each construction. Doing that inside the argument list
                 * meant a throwaway formatter and a throwaway Date on every pass, all to
                 * produce a string that only changes when the billing period does.
                 *
                 * Kept inside remember rather than hoisted to a file-level constant on purpose:
                 * SimpleDateFormat is not thread safe, and a shared instance would be an easy
                 * thing for someone to later touch off the main thread.
                 */
                PlainRow(
                    title = "Renews",
                    value = remember(tenant?.currentPeriodEnd) {
                        tenant?.currentPeriodEnd
                            ?.takeIf { it > 0L }
                            ?.let {
                                java.text.SimpleDateFormat(
                                    "d MMM yyyy",
                                    java.util.Locale.getDefault(),
                                ).format(java.util.Date(it))
                            }
                            ?: "No renewal date"
                    },
                )
                Hairline()
                PlainRow(
                    title = "Features",
                    value = tenant?.features
                        ?.filterNot { it == WILDCARD_FEATURE }
                        ?.joinToString(", ") { featureLabel(it) }
                        ?.ifBlank { "None" }
                        ?: "\u2026",
                )
            }

            /*
             * No tenant at all means the claims are missing: either the bootstrap never ran, or
             * the token predates it. Both are fixed by signing out and back in, which is worth
             * saying plainly rather than leaving an account page full of ellipses.
             */
            if (tenant == null) {
                SupportCard {
                    Text(
                        text = "Workspace not loaded",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.padding(top = 6.dp))
                    Text(
                        text = loadError
                            ?: "Your sign-in has no workspace attached yet. Sign out and back " +
                            "in to pick up the new permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            /*
             * Subscription and Link your website both moved out of here. Neither was an account
             * detail: billing lives in Settings > Subscription, and the domain flow lives in
             * Settings > Link your website, which is also the only place the single-domain rule
             * is enforced. Duplicating the entry points here meant two routes into the same
             * screen with different surrounding context.
             *
             * Add social media accounts, Add another inbox source and Sign out have all been
             * removed from this screen. The first two were entry points to a Coming soon page,
             * which is not something an account screen should advertise as if it were a feature.
             * Sign out was stranded mid-page behind those two buttons; it now lives alone, in
             * red, at the very bottom of Settings, which is where a destructive action is
             * looked for. Do not add any of the three back here.
             *
             * Delete account and workspace, by contrast, has moved IN, from Storage and data.
             * Deleting the tenant is an account action, not a storage one, and it was sitting
             * next to Clear cache where a mis-tap is cheap.
             */
            if (tenant != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(GroupedCardShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    PlainRow(
                        title = "Delete account and workspace",
                        danger = true,
                        onClick = debounced { confirmDelete = true },
                    )
                }
            }

            if (confirmDelete) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete account and workspace") },
                    text = {
                        Text(
                            "This removes the workspace, its linked websites, its leads and its " +
                                "API keys. It cannot be undone.\n\nIt also cannot be done from " +
                                "the app. Unwinding a tenant safely is a server job, and a " +
                                "button that half-finishes it would leave a live widget " +
                                "pointing at a workspace that no longer exists. Email " +
                                "info@keykraftt.com from this address and it is actioned for you.",
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { confirmDelete = false },
                        ) { Text("Close") }
                    },
                )
            }
        }
    }
}

/** Row separator inside an icon-less card. Indented to the text, not to a missing tile. */
@Composable
private fun Hairline() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        thickness = Dp.Hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** One profile field while the pencil is active. Same card, same insets, editable. */
@Composable
private fun EditField(
    label: String,
    value: String,
    keyboard: KeyboardType,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            imeAction = ImeAction.Next,
        ),
        shape = MaterialTheme.shapes.medium,
    )
}
