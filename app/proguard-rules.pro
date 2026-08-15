#
# R8 configuration.
#
# READ THIS BEFORE ADDING RULES.
#
# The previous version of this file opened with "Minification is off for release in this build".
# That was false and had been for some time. Release sets isMinifyEnabled = true and
# isShrinkResources = true, and because this project is on AGP 8.7.3 with no
# android.enableR8.fullMode entry in gradle.properties, R8 runs in FULL MODE - full mode has
# been the default since AGP 8.0. So the most aggressive optimizer setting has been shipping
# against an empty rules file. Everything below exists to make that safe and inspectable.
#
# The temptation with R8 is to paste broad -keep rules until the crash stops. Every such rule
# switches off optimization for the classes it names, and a wide enough one silently disables
# most of the benefit. Rules here are deliberately narrow, and each says why it exists.
#

# ----------------------------------------------------------------------------------------------
# Crash reports must stay readable.
# ----------------------------------------------------------------------------------------------
#
# Without this, every release stack trace is obfuscated class names and no line numbers, which
# makes a production crash close to unactionable. Keeping these two attributes costs a small
# amount of APK size and no runtime performance.
#
# renamesourcefileattribute collapses the original filename to "SourceFile", so the source name
# is not leaked while the line numbers stay usable through mapping.txt.
#
# Keep build/outputs/mapping/release/mapping.txt for every build you ship. Without the mapping
# file for that exact build, the line numbers below cannot be turned back into source positions.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin coroutines and suspend functions carry generic signatures that some libraries inspect
# at runtime, and annotations are used by Room's generated code.
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault

# ----------------------------------------------------------------------------------------------
# What this app does NOT need, and why - do not add these back without evidence.
# ----------------------------------------------------------------------------------------------
#
# Firebase model classes: NOT needed. Every read in this codebase pulls primitives out of a
# DataSnapshot by hand - getValue(String::class.java), getValue(Long::class.java),
# getValue(Boolean::class.java) - in SupportRepository, SupportApi and MessageWatchService.
# Nothing calls toObject() or getValue() with an app-owned class, so there is no reflective
# construction for R8 to break. If you ever switch to reflective deserialisation, you MUST add:
#
#   -keepclassmembers class com.codexce.supportchat.data.model.** {
#       <init>();
#       <fields>;
#   }
#
# Enums: NOT needed. There is no valueOf() or values() reflection anywhere in the app, so the
# usual -keepclassmembers enum rule would only block optimization for nothing.
#
# Room, Compose, Firebase, Coil and coroutines: NOT needed. Each ships its own consumer rules
# inside its AAR, and those are applied automatically. Re-declaring them here does not make the
# build safer, it just duplicates rules that may drift out of date with the library.
#
# Manifest components (MainActivity, MonoIconAlias, SupportMessagingService, MessageWatchService,
# BootReceiver, InitializationProvider): NOT needed. AGP generates keep rules from the merged
# manifest, so every class named there is already a root.

# ----------------------------------------------------------------------------------------------
# Diagnostics.
# ----------------------------------------------------------------------------------------------
#
# These write reports rather than changing behaviour, and they are the difference between
# "the release build crashed" and knowing which class R8 removed.
#
#   usage.txt  - everything R8 stripped. Check here first when something is missing at runtime.
#   seeds.txt  - every class kept as a root. If a class you expected to shrink is in here, some
#                rule is keeping it alive and costing you size.
#
# Both land in app/build/outputs/mapping/<variant>/.
-printusage build/outputs/mapping/release/usage.txt
-printseeds build/outputs/mapping/release/seeds.txt
