# Lifecycle-aware fork

New integrations should use `Tutorial`, `Step`, `TutorialSession` and `AndroidTutorialHost` from
`uk.co.deanwild.materialshowcaseview.session`. Existing builders remain available.

The current fork release is available from JitPack:

```groovy
implementation 'com.github.T1mmyK0:MaterialShowcaseView:1.8.1'
```

Version 1.8.1 fixes session panel placement around highlighted controls. See the
[release notes](CHANGELOG.md) for details.

- [Session API and contracts](docs/SESSION_API.md)
- [Java AI tutorial integration and migration](docs/AI_TUTORIAL_MIGRATION.md)
- [Source audit and compatibility decisions](docs/AUDIT.md)
- [Release notes](CHANGELOG.md)
- [Review phases](docs/REVIEW_PHASES.md) and [validation/remaining limits](docs/VALIDATION.md)

Run `./gradlew :library:testDebugUnitTest :sample:assembleDebug` to test and build. The sample menu's
**Lifecycle AI tutorials** screen exercises changing providers, policy dialogs, long prompts and
targets that can be hidden, disabled, moved or removed. The optional `:lifecycle` module supplies
AndroidX lifecycle, Back and RecyclerView adapters. All modules require Android 7.0 (API 24) or newer;
the core library remains free of external runtime dependencies.

*Looking for collaborators to help maintain this library, drop me a line at me@deanwild.co.uk if you want to help.*

# MaterialShowcaseView
A Material Design themed ShowcaseView for Android


This library is heavily inspired by the original [ShowcaseView library][1].

Since Google introduced the Material design philosophy I have seen quite a few apps with a nice clean, flat showcase view (the Youtube app is a good example). The only library out there however is the [original one][1]. This was a great library for a long time but the theming is now looking a bit dated.

![Logo](http://i.imgur.com/QIMYRJh.png)


![Animation][2]

# Gradle
--------

[![jitpack][4]][5]

For the published release, add the JitPack repository to your project's
`settings.gradle` repositories. [Why?](#why-jitpack)

/settings.gradle
```groovy
dependencyResolutionManagement {
	repositories {
		google()
		mavenCentral()
		maven { url "https://jitpack.io" }
	}
}
```

Then add the dependency to your module's build.gradle:

/app/build.gradle
```groovy
implementation 'com.github.deano2390:MaterialShowcaseView:1.3.7'
```

NOTE: Some people have mentioned that they needed to add the @aar suffix to get it to resolve from JitPack:
```groovy
implementation 'com.github.deano2390:MaterialShowcaseView:1.3.7@aar'
```

# Building from source

The source project uses the following stable versions, checked on October 5, 2026:

| Component | Version |
| --- | --- |
| Android Studio | Rabbit 1 / 2026.2.1 |
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.8.0 |
| Android compile SDK | Android 17, API 37 |
| Sample target SDK | API 37 |
| Android SDK Build Tools | 37.0.0 |
| Java source and bytecode | 17 |
| AndroidX AppCompat / Core | 1.8.0 / 1.19.1 |
| Material Components | 1.14.0 |

Install SDK Platform 37 and Build Tools 37.0.0 through Android Studio's SDK
Manager. Set the SDK location in `local.properties` (`sdk.dir=...`) or through
`ANDROID_HOME`. Use JDK 17 or a compatible newer JDK; builds have been verified
with Android Studio's bundled JDK 25.

JitPack selects JDK 17 through `jitpack.yml`. The Gradle daemon uses the configured
Gradle JDK or `JAVA_HOME`; there is no repository-wide daemon JVM override, so CI
does not download Android Studio's JetBrains runtime. The core library already
defines a Maven publication, which JitPack builds with `publishToMavenLocal`.

In Android Studio, select the bundled JDK as the Gradle JDK and sync the project.
For command-line builds, set `JAVA_HOME` to your JDK directory and run:

```sh
./gradlew clean build
```

On Windows, use `gradlew.bat clean build`. The library AARs are generated in
`library/build/outputs/aar/`, and sample APKs in `sample/build/outputs/apk/`.

The library, optional lifecycle adapter and sample require Android 7.0 (API 24) or
newer. This source version drops support for API 12–23 in the core and API 23 in
the lifecycle adapter and sample; consuming apps must set `minSdk` to at least 24.
The core remains free of external runtime dependencies. The sample uses AndroidX
and Material Components and handles system-bar and display-cutout insets for
edge-to-edge layouts. Normal lint checks are enabled, including target SDK checks.

Android libraries specify a compile SDK; the consuming application controls the
target SDK and Android runtime behavior. Consumers of this source build need the
37 compile SDK. The sample opts into API 37 behavior. The JitPack 1.3.7 dependency
shown above is the previously published release; these source changes require a
new release or using the local `:library` module.

# How to use
--------
This is the basic usage of a single showcase view, you should check out the sample app for more advanced usage.

```java

	// single example
	new MaterialShowcaseView.Builder(this)
		.setTarget(mButtonShow)
		.setDismissText("GOT IT")
		.setContentText("This is some amazing feature you should know about")
		.setDelay(withDelay) // optional but starting animations immediately in onCreate can make them choppy
		.singleUse(SHOWCASE_ID) // provide a unique ID used to ensure it is only shown once
		.show();
                
                
                
                
	// sequence example            
	ShowcaseConfig config = new ShowcaseConfig();
	config.setDelay(500); // half second between each showcase view

	MaterialShowcaseSequence sequence = new MaterialShowcaseSequence(this, SHOWCASE_ID);

	sequence.setConfig(config);

	sequence.addSequenceItem(mButtonOne,
		"This is button one", "GOT IT");

	sequence.addSequenceItem(mButtonTwo,
		"This is button two", "GOT IT");

	sequence.addSequenceItem(mButtonThree,
		"This is button three", "GOT IT");

	sequence.start();
                
```

# Why Jitpack
------------
Publishing libraries to Maven is a chore that takes time and effort. Jitpack.io allows me to release without ever leaving GitHub so I can release easily and more often.

# Apps using MaterialShowcaseView
---------------------------------

  * [Say It! - English Learning](https://play.google.com/store/apps/details?id=com.cesarsk.say_it) : An Android App aimed to improve your English Pronunciation. 
    * [Github Page](https://github.com/cesarsk/say_it)
    
  * [Queskr](https://play.google.com/store/apps/details?id=com.queskr.www.queskrandroidapp) : Social Q&A at your fingertips

# Learning Resources
[https://medium.com/@yashgirdhar/android-material-showcase-view-part-1-22abd5c65b85][6]

[https://1bucketlist.blogspot.com/2017/03/android-material-showcase-view-1.html][7]

[https://blog.fossasia.org/tag/material-showcase-view/][8]



# License
-------

    Copyright 2015 Dean Wild

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.





[1]: https://github.com/amlcurran/ShowcaseView
[2]: http://i.imgur.com/rFHENgz.gif
[3]: https://code.google.com/p/android-flowtextview/
[4]: https://img.shields.io/github/release/deano2390/MaterialShowcaseView.svg?label=JitPack
[5]: https://jitpack.io/#deano2390/MaterialShowcaseView
[6]: https://medium.com/@yashgirdhar/android-material-showcase-view-part-1-22abd5c65b85
[7]: https://1bucketlist.blogspot.com/2017/03/android-material-showcase-view-1.html
[8]: https://blog.fossasia.org/tag/material-showcase-view/
