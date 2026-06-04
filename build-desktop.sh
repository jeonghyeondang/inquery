#!/bin/bash
set -e

# Homebrew PATH (bash doesn't source .zshrc)
eval "$(/opt/homebrew/bin/brew shellenv)"
export PATH="/opt/homebrew/opt/node@22/bin:/opt/homebrew/opt/openjdk@21/bin:/opt/homebrew/opt/postgresql@18/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/inquery-server"
CLIENT_DIR="$SCRIPT_DIR/inquery-client-svelte"
TAURI_DIR="$CLIENT_DIR/src-tauri"
RESOURCES_DIR="$TAURI_DIR/resources"
BINARIES_DIR="$TAURI_DIR/binaries"

# Apple Silicon (arm64) only
if [ "$(uname -m)" != "arm64" ]; then
    echo "ERROR: This build only supports Apple Silicon (arm64). Intel Macs are not supported."
    exit 1
fi

TARGET_TRIPLE="aarch64-apple-darwin"

echo "============================================"
echo "  Inquery Desktop Build Script"
echo "  Target: $TARGET_TRIPLE (Apple Silicon)"
echo "============================================"

# Step 1: Build Java backend
echo ""
echo "[1/5] Building Java backend..."
cd "$SERVER_DIR"
mvn clean package -DskipTests -q 2>/dev/null
JAR_PATH="$SERVER_DIR/inquery-server-web-start/target/inquery-server-web-start.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: JAR not found at $JAR_PATH"
    exit 1
fi
echo "  JAR built: $JAR_PATH"

# Step 2: Compile pgvector extension for embedded PostgreSQL
echo ""
echo "[2/5] Building pgvector extension..."
PGVECTOR_VERSION="0.8.2"
PGVECTOR_RESOURCES="$RESOURCES_DIR/pgvector"

# IMPORTANT: This version MUST match embedded-postgres-binaries-bom in pom.xml
PG_MAJOR=18

compile_pgvector() {
    local PG_CONFIG="$1"
    local PG_VER
    PG_VER=$("$PG_CONFIG" --version 2>/dev/null)
    echo "  Compiling pgvector $PGVECTOR_VERSION for $PG_VER"

    PGVECTOR_BUILD_DIR=$(mktemp -d)
    git clone --depth 1 --branch "v${PGVECTOR_VERSION}" \
        https://github.com/pgvector/pgvector.git "$PGVECTOR_BUILD_DIR" 2>/dev/null

    cd "$PGVECTOR_BUILD_DIR"

    local JOBS
    JOBS=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)

    if [ "$(uname)" = "Darwin" ]; then
        local CORRECT_SYSROOT
        CORRECT_SYSROOT=$(xcrun --show-sdk-path 2>/dev/null)
        echo "  macOS SDK: $CORRECT_SYSROOT"

        local FIXED_CPPFLAGS
        local FIXED_LDFLAGS
        FIXED_CPPFLAGS=$("$PG_CONFIG" --cppflags | sed "s|-isysroot [^ ]*|-isysroot $CORRECT_SYSROOT|g")
        FIXED_LDFLAGS=$("$PG_CONFIG" --ldflags | sed "s|-isysroot [^ ]*|-isysroot $CORRECT_SYSROOT|g")

        make PG_CONFIG="$PG_CONFIG" CPPFLAGS="$FIXED_CPPFLAGS" LDFLAGS="$FIXED_LDFLAGS" -j"$JOBS"
    else
        make PG_CONFIG="$PG_CONFIG" -j"$JOBS"
    fi

    mkdir -p "$PGVECTOR_RESOURCES/lib"
    mkdir -p "$PGVECTOR_RESOURCES/share/extension"

    # macOS produces .dylib, Linux produces .so
    if [ -f vector.dylib ]; then
        cp vector.dylib "$PGVECTOR_RESOURCES/lib/"
    elif [ -f vector.so ]; then
        cp vector.so "$PGVECTOR_RESOURCES/lib/"
    else
        echo "  WARNING: No compiled pgvector library found"
    fi

    cp vector.control "$PGVECTOR_RESOURCES/share/extension/"
    cp sql/vector--*.sql "$PGVECTOR_RESOURCES/share/extension/"

    cd "$SCRIPT_DIR"
    rm -rf "$PGVECTOR_BUILD_DIR"
    echo "  pgvector $PGVECTOR_VERSION compiled successfully"
}

# Find pg_config for the required PG version
PG_CONFIG=""

if command -v brew &> /dev/null; then
    BREW_PG_CONFIG="$(brew --prefix postgresql@$PG_MAJOR 2>/dev/null)/bin/pg_config"
    if [ -f "$BREW_PG_CONFIG" ]; then
        PG_CONFIG="$BREW_PG_CONFIG"
        echo "  Using Homebrew PostgreSQL@$PG_MAJOR"
    fi
fi

if [ -n "$PG_CONFIG" ]; then
    compile_pgvector "$PG_CONFIG"
else
    echo "  ERROR: postgresql@$PG_MAJOR is required but not installed."
    echo "  Run: brew install postgresql@$PG_MAJOR"
    echo "  pgvector will NOT be available in the desktop build."
fi

# Step 3: Create custom JRE with jlink
echo ""
echo "[3/5] Creating custom JRE with jlink..."
JRE_DIR="$RESOURCES_DIR/jre"
rm -rf "$JRE_DIR"

JAVA_HOME_DIR=$(java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk '{print $3}')
JMODS_DIR="$JAVA_HOME_DIR/jmods"

if [ ! -d "$JMODS_DIR" ]; then
    echo "  jmods not found at $JMODS_DIR, using JAVA_HOME fallback..."
    JMODS_DIR="$JAVA_HOME/jmods"
fi

if [ ! -d "$JMODS_DIR" ]; then
    echo "  WARNING: jmods directory not found. Copying full JRE instead..."
    mkdir -p "$JRE_DIR"
    cp -rL "$JAVA_HOME_DIR"/* "$JRE_DIR/"
else
    jlink \
        --module-path "$JMODS_DIR" \
        --add-modules java.base,java.sql,java.naming,java.management,java.instrument,java.desktop,java.security.jgss,java.net.http,java.compiler,java.datatransfer,java.logging,java.prefs,java.rmi,java.scripting,java.xml,java.xml.crypto,java.transaction.xa,jdk.unsupported,jdk.crypto.ec,jdk.management,jdk.zipfs,jdk.localedata \
        --output "$JRE_DIR" \
        --strip-debug \
        --compress 2 \
        --no-header-files \
        --no-man-pages
    echo "  Custom JRE created at $JRE_DIR"
fi

# Dereference symlinks in JRE (Tauri bundler cannot handle symlinks)
SYMLINK_COUNT=$(find "$JRE_DIR" -type l | wc -l | tr -d ' ')
if [ "$SYMLINK_COUNT" -gt 0 ]; then
    echo "  Resolving $SYMLINK_COUNT symlinks in JRE..."
    rsync -rL --delete "$JRE_DIR/" "$JRE_DIR-resolved/"
    rm -rf "$JRE_DIR"
    mv "$JRE_DIR-resolved" "$JRE_DIR"
    echo "  Symlinks resolved."
fi

# Remove legal/ dir (license text not needed at runtime, causes macOS provenance issues)
rm -rf "$JRE_DIR/legal"
chmod -R u+w "$JRE_DIR"

# Step 4: Copy JAR to resources
echo ""
echo "[4/5] Copying artifacts..."
mkdir -p "$RESOURCES_DIR"
cp "$JAR_PATH" "$RESOURCES_DIR/inquery-server.jar"
echo "  JAR copied to $RESOURCES_DIR/inquery-server.jar"

# Ensure sidecar script exists with correct target triple
SIDECAR_SCRIPT="$BINARIES_DIR/inquery-server-$TARGET_TRIPLE"
if [ ! -f "$SIDECAR_SCRIPT" ]; then
    echo "  WARNING: Sidecar script not found for $TARGET_TRIPLE, creating..."
    mkdir -p "$BINARIES_DIR"
    cat > "$SIDECAR_SCRIPT" << 'SIDECAR_EOF'
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -d "$SCRIPT_DIR/../Resources/resources" ]; then
    RESOURCES_DIR="$(cd "$SCRIPT_DIR/../Resources/resources" && pwd)"
elif [ -d "$SCRIPT_DIR/../resources" ]; then
    RESOURCES_DIR="$(cd "$SCRIPT_DIR/../resources" && pwd)"
else
    RESOURCES_DIR="$(dirname "$SCRIPT_DIR")/Resources/resources"
fi

JAVA_HOME="$RESOURCES_DIR/jre"
JAR_PATH="$RESOURCES_DIR/inquery-server.jar"
PGVECTOR_DIR="$RESOURCES_DIR/pgvector"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

PGVECTOR_OPT=""
if [ -d "$PGVECTOR_DIR" ]; then
    PGVECTOR_OPT="-Dinquery.desktop.pgvector.dir=$PGVECTOR_DIR"
fi

exec "$JAVA_HOME/bin/java" \
    --add-opens=java.base/java.nio=ALL-UNNAMED \
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    -Xmx512m \
    -Dspring.profiles.active=desktop \
    -Dserver.port=10821 \
    $PGVECTOR_OPT \
    -jar "$JAR_PATH"
SIDECAR_EOF
    chmod +x "$SIDECAR_SCRIPT"
fi

# Step 5: Build Tauri app
echo ""
echo "[5/5] Building Tauri desktop app..."
cd "$CLIENT_DIR"
export TAURI_ENV=true
npx tauri build

echo ""
echo "============================================"
echo "  Build complete!"
echo "  DMG location:"
echo "  ${TAURI_DIR}/target/release/bundle/dmg/"
echo "============================================"
ls -la "${TAURI_DIR}/target/release/bundle/dmg/" 2>/dev/null || echo "  check target/release/bundle/dmg/ after build"
