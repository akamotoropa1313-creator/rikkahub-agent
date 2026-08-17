from pathlib import Path

build_file = Path("app/build.gradle.kts")
text = build_file.read_text(encoding="utf-8")

marker = "        debug {\n"
start = text.index(marker)
end = text.index("        }\n", start) + len("        }\n")

benchmark = '''        create("benchmark") {
            // Keep runtime characteristics comparable to the optimized release build,
            // while allowing CI artifacts to be installed alongside production/debug.
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
'''

if 'create("benchmark")' in text:
    raise SystemExit("benchmark build type already exists; refusing to inject it twice")

text = text[:end] + benchmark + text[end:]
build_file.write_text(text, encoding="utf-8")
