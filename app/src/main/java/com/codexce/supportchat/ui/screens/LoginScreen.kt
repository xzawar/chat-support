package com.codexce.supportchat.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.codexce.supportchat.ui.components.debounced
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexce.supportchat.R
import com.codexce.supportchat.ui.components.ErrorBanner
import com.codexce.supportchat.viewmodel.AuthViewModel

/*
 * Login gate, laid out to match the supplied reference: an illustration filling the top of the
 * screen, then a cream panel with the heading and two pill buttons.
 *
 * The colours below are deliberately hard-coded rather than pulled from MaterialTheme. This is a
 * branded first-run screen and the reference is a single fixed colour scheme, so it looks the same
 * whether the device is in light or dark mode. Every other screen still follows the theme.
 *
 * Two honest notes about how close this gets to the reference:
 *
 *  - login_hero.png is cropped out of the reference JPG itself, so it is only 552px wide and will
 *    be scaled up roughly 2x on a 1080p phone. It will look slightly soft. A vector or a
 *    full-resolution export of that illustration is the only way to fix that.
 *  - The wordmark uses the system sans-serif at its heaviest weight. The typeface in the reference
 *    could not be identified or downloaded here. Drop a .ttf into res/font and swap WordmarkFont
 *    below for an exact match; Poppins ExtraBold and Nunito Black are close free stand-ins.
 */

private val SheetCream = Color(0xFFF5F1EA)
private val HeroBackdrop = Color(0xFFF7FBFF)
private val Ink = Color(0xFF111111)
private val MutedInk = Color(0xFF6E6A65)
private val HairlineBorder = Color(0x1A000000)
private val FieldBorder = Color(0x33000000)

// Swap this for FontFamily(Font(R.font.your_font)) once the real typeface is available.
private val WordmarkFont = FontFamily.SansSerif

@Composable
fun LoginScreen(authViewModel: AuthViewModel) {
    val auth by authViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var emailExpanded by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    // The reference gives the illustration a little under two thirds of the screen. Deriving it
    // from the screen height keeps that ratio on tall and short devices alike.
    val heroHeight = (LocalConfiguration.current.screenHeightDp * 0.58f).dp

    Box(
        Modifier
            .fillMaxSize()
            .background(SheetCream),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // HERO -------------------------------------------------------------------
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .background(HeroBackdrop),
            ) {
                Image(
                    painter = painterResource(R.drawable.login_hero),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                )
                Text(
                    text = "Support Chat",
                    fontFamily = WordmarkFont,
                    fontWeight = FontWeight.Black,
                    fontSize = 40.sp,
                    letterSpacing = (-1.2).sp,
                    color = Ink,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 24.dp),
                )
            }

            // SHEET ------------------------------------------------------------------
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(SheetCream)
                    .navigationBarsPadding()
                    .padding(start = 28.dp, top = 30.dp, end = 28.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Sign in to get started",
                    color = MutedInk,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )

                auth.error?.let { message ->
                    Spacer(Modifier.height(16.dp))
                    ErrorBanner(message = message, onDismiss = authViewModel::clearError)
                }

                Spacer(Modifier.height(22.dp))

                // Google pill: white with a hairline edge, exactly as in the reference.
                Button(
                    onClick = debounced {
                        authViewModel.signInWithGoogle(
                            context = context,
                            serverClientId = context.getString(R.string.google_web_client_id),
                        )
                    },
                    enabled = !auth.submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, HairlineBorder),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Ink,
                        disabledContainerColor = Color.White,
                        disabledContentColor = MutedInk,
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_google_g),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = "Sign in with Google",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // The reference puts an Apple button here. You said Apple is not added, so this
                // slot keeps the email method the app already relies on.
                Button(
                    onClick = debounced { emailExpanded = !emailExpanded },
                    enabled = !auth.submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ink,
                        contentColor = Color.White,
                        disabledContainerColor = Ink.copy(alpha = 0.4f),
                        disabledContentColor = Color.White,
                    ),
                ) {
                    Text(
                        text = if (emailExpanded) "Use another method" else "Sign in with Email",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                AnimatedVisibility(
                    visible = emailExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(20.dp))
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Email") },
                            singleLine = true,
                            enabled = !auth.submitting,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            ),
                            shape = RoundedCornerShape(16.dp),
                            colors = loginFieldColors(),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Password") },
                            singleLine = true,
                            enabled = !auth.submitting,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            shape = RoundedCornerShape(16.dp),
                            colors = loginFieldColors(),
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = debounced { authViewModel.signIn(email, password) },
                            enabled = !auth.submitting &&
                                email.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(28.dp),
                            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Ink,
                                contentColor = Color.White,
                                disabledContainerColor = Ink.copy(alpha = 0.25f),
                                disabledContentColor = Color.White,
                            ),
                        ) {
                            if (auth.submitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                )
                            } else {
                                Text(
                                    text = "Sign in",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/*
 * The sheet is a fixed cream colour, so the text fields cannot inherit theme colours: in dark mode
 * that would paint light text onto a light panel.
 */
@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    disabledTextColor = MutedInk,
    cursorColor = Ink,
    focusedBorderColor = Ink,
    unfocusedBorderColor = FieldBorder,
    disabledBorderColor = HairlineBorder,
    focusedLabelColor = MutedInk,
    unfocusedLabelColor = MutedInk,
    disabledLabelColor = MutedInk,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    disabledContainerColor = Color.White,
)
