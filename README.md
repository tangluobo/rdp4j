# rdp4j

`rdp4j` is the standalone Maven project containing Tomato's RDP client,
rendering, clipboard, audio, keyboard mapping, NLA, and TLS implementation.

## Build and install locally

```shell
../mvnw -f pom.xml clean install
```

On Windows:

```powershell
..\mvnw.cmd -f pom.xml clean install
```

The artifact coordinates are:

```xml
<dependency>
    <groupId>com.tangluobo</groupId>
    <artifactId>rdp4j</artifactId>
    <version>1.0.2</version>
</dependency>
```

The JAR declares the automatic JPMS module name `rdp4j`. Keyboard maps are
packaged in the JAR under `/keymaps`.
