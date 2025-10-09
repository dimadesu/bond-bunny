# Bond Bunny. SRT to SRTLA Bonding App for Android

This is Android app that allows listening on local port for SRT stream and then forwarding SRT packets via multiple connections, such as cellular, Wi-Fi and Ethernet, to remote SRTLA server like [Belabox Cloud](https://belabox.net/). This process is also known as bonding.

This is compatible with any apps that can stream SRT and it essentially extends them with bonding on the same device.

Android implementation of [SRTLA](https://github.com/BELABOX/srtla).

Some ideas borrowed from [Moblin](https://github.com/eerimoq/moblin) and [Moblink](https://github.com/eerimoq/moblink).

Discord https://discord.gg/2UzEkU2AJW

## Clean SRTLA Implementation

Bond Bunny now includes a **clean SRTLA implementation** that uses the battle-tested BELABOX SRTLA code as a git submodule. This provides:

- ✅ **Proven reliability**: Uses the original, well-tested SRTLA protocol implementation
- ✅ **Simple architecture**: Clean JNI wrapper around proven C code
- ✅ **Better performance**: Direct integration without complex custom logic
- ✅ **Easy maintenance**: Submodule approach keeps SRTLA code pristine

The clean implementation is now the default when you launch the app. It uses `CleanMainActivity` and `CleanSrtlaService` for a streamlined experience.

### Technical Architecture

- **srtla/**: Git submodule containing original BELABOX SRTLA C code
- **srtla_android_wrapper.cpp**: JNI bridge for Android integration
- **SRTLANative.java**: Clean Java interface to native functions
- **CleanSrtlaService.java**: Simplified Android service
- **CleanMainActivity.java**: Streamlined UI

## Apps that can work together

- [MediaSrvr](https://github.com/dimadesu/MediaSrvr) - Runs RTMP server on Android phone. You can publish RTMP stream to it from an action camera, for example.
- [LifeStreamer](https://github.com/dimadesu/LifeStreamer) - Can use RTMP as source: playback RTMP stream from server and restream it as SRT with great dynamic bitrate.
- Bond Bunny - You can use LifeStreamer to publish SRT stream into Bond Bunny.

## Installation Instructions

Not sure yet when app will be ready for publishing to Google Play store if ever.

For now I'll be publishing .apk files periodically via GitHub releases on this repo.

- Open https://github.com/dimadesu/bond-bunny/releases on the device that you want to install it on
- Download .apk file
- Install
- Enjoy!

## Apps that can stream SRT on Android

- IRL Pro (free)
- Larix Broadcaster (subscription)
- Larix Screencaster (subscription)
- Can do HDMI/USB/UVC as input:
  - USB Camera (free with ads) / USB Camera Pro (one-time payment to remove ads)
  - CameraFi (free version with ads or subscription)

![Screenshort Bond Bunny](./docs/Screenshot-Bond-Bunny.jpg)
