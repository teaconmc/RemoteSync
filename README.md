# RemoteSync

... is forbidden magic that keeps your mods updated.

## Preface

The keywords "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", 
"SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be 
interpreted as described in [RFC 2119](https://tools.ietf.org/html/rfc2119).

## Tl;dr: how to?

  1. This mod requires Forge (FML, to be exact). 
     - For any Forge versions 28.1.65 or onward, drop this into your `mods` 
       folder. Still, you SHOULD use sufficiently new Minecraft and Forge 
       versions (i.e. those in active development or LTS).   
       You MUST NOT try using RemoteSync to load RemoteSync itself. It will 
       never work.
     - For any Forge versions 25.0.0 - 28.1.64: this *might* work, but you 
       *MUST* somehow put this into your classpath.  
       If you choose these Forge versions, you will also not receive any 
       technical support here. Updates.
     - For any Forge versions 14.23.5.2855 or downward: this does NOT work 
       on older versions, at all. See "Alternatives" section below for 
       possible solutions.
  2. Prepare [a collection of PGP public keys](#trust-model). You SHOULD make 
     sure that your users trust these public keys.
  3. Host all of your mods and [your mod list](#mod-list-format) publicly. 
     How to keep your list updated is up to you. 
  4. Prepare your [`remote_sync.json`](#config-file-remote_syncjson).
  5. When packaging, only include the key rings and config file.

<!-- 
You might want to ask why 28.1.65? Because it includes the following commit:
https://github.com/MinecraftForge/MinecraftForge/commit/3bf6c17bb8ae924d0bfbcd896624dc59480ed8dd
-->

## Workflow

RemoteSync works in the following way:

  1. The "mod" itself *MUST* be in the `mods` directory for technical 
     reason.
  2. When game starts, RemoteSync loads the config file `remote_sync.json` 
     (under your `gameDir`) and load public key rings from the path 
     specified in the config file.
  3. RemoteSync will then fetch the mod list from the URL given in the 
     config file `remote_sync.json` (under your `gameDir`). RemoteSync 
     caches the mod list file and uses the cached version if remote does 
     not have updates.
  3. RemoteSync then downloads and caches all mods and their signatures 
     according to the mod list, verify their identity and handle them 
     to FML if and only if verified.

## Trust Model

RemoteSync uses [PGP][ref-1] to verify if a mod file is trustworthy. 
Naturally, it means that the trust model of RemoteSync is [the 
Web of Trust][ref-2]. 

Put it simple, users establish trusts by trusting third parties' public 
key(s) *and thus* public keys that those third parties trust. Example: 

  1. Alice and Bob meets in real life, exchanged and signed their public 
     keys. They thus start to trust each other. 
  2. Later, Bob and Clara meets in real life, exchanged and signed their 
     public keys. Bob now trusts Clara.
  3. Now, Alice can trust Clara because Alice trusts Bob and Bob trusts 
     Clara. 
  4. The network expands organically as more people (e.g. David, Elise, 
     Frank, ...) join in the same manner.

RemoteSync uses detached signatures, so that mod files themselves are 
verbatim.

[ref-1]: https://en.wikipedia.org/wiki/Pretty_Good_Privacy
[ref-2]: https://en.wikipedia.org/wiki/Web_of_trust

## Config file `remote_sync.json` 

RemoteSync intentionally does not create this file. It is upon modpack 
creators to create and properly configure this file. 

The following is explanation of this file:

```json
{
  "modDir": "remote_synced_mods",
  "keyRingPath": "public_keys.asc",
  "modList": "http://example.com/",
  "timeout": 15000
}
```

  - `modDir` is the path to the directory where RemoteSync stores downloaded 
    mods. It is relative to your game directory (a.k.a. the `.minecraft` 
    directory).
  - `keyRingPath` is the path to the PGP key ring files whose contents are 
    public keys that users trust. It is also relative to your game directory.
  - `modList` is the URL to the mod list. The format of mod list is in next 
    section.
  - `timeout` is the number of milliseconds for all remote connections to wait 
    before giving up.

## Mod List Format

The mod list is a JSON file containing exactly one JSON Array that looks like:

```json
[
  {
    "name": "mod-a-1.0.jar",
    "file": "http://example.invalid/mod-a-1.0.jar",
    "sig": "http://example.invalid/mod-b-1.0.jar.sig"
  },
  {
    "name": "mod-b-1.0.jar",
    "file": "http://example.invalid/mod-b-1.0.jar",
    "sig": "http://example.invalid/mod-b-1.0.jar.sig"
  }
]
```

  - `name` is the file name to use for the local cache.
  - `file` is the URL to the mod file itself. 
    URL must be direct and publicly accessible.
  - `sig` is the URL to the signature for the mod file. 
    Again, URL must be direct publicly accessible.
  - The same structure can be repeated as many times as needed.

## Alternatives

[cpw's serverpackloactor][ref-3] is a good candidate to consider if you feel 
uncomfortable about setting up PGP keys. 

If you accept using launch arguments, you can try [the `--fml.mavenRoots` 
option][ref-4]. Do note that there is also `--fml.modLists` option which lets 
you use several list files instead of stuffing everything in the arguments. 
These list files MUST have extension of `list` (i.e. `foo.list`, `bar.list`), 
and their contents are simply maven coordinates, one per line.

Older versions of Forge (for Minecraft 1.12.2 or older) has [two options with 
similar functionalities: `--mods` and `--modListFile`][ref-5].

There is currently no alternatives if you are from Fabric ecosystem. I have 
started looking into it, but it is very tricky...

[ref-3]: https://github.com/cpw/serverpacklocator
[ref-4]: https://github.com/MinecraftForge/MinecraftForge/issues/5495#issuecomment-464916093
[ref-5]: https://github.com/MinecraftForge/FML/wiki/New-JSON-Modlist-format

## To developers who want to go down the rabbit hole

Directly import this project as a *Gradle Project* to your IDE, 
no special configuration needed. 

It is worth noting that this project does not depend on Minecraft 
itself, only ForgeSPI and ModLauncher. One implication is that you 
cannot test it directly in your IDE unless you manually set it up.
