#!/bin/bash

    # Set strict error handling
    set -e

    # Constants and defaults
    PORT=${PORT:-8080}
    PROMETHEUS_PORT=${PROMETHEUS_PORT:-8081}
    ROOT_DIR=${ROOT_DIR:-/app}
    JAR_PATH=${JAR_PATH:-/app/wiremock.jar}
    VALUES_DIR=${VALUES_DIR:-/environment}
    LOG_PREFIX="[WireMock]"

    # Function to log messages with timestamp
    log() {
      echo "$(date +'%Y-%m-%d %H:%M:%S') ${LOG_PREFIX} $1"
    }

    # Function to log errors
    error() {
      echo "$(date +'%Y-%m-%d %H:%M:%S') ${LOG_PREFIX} ERROR: $1" >&2
    }

    # Function to extract a value from a values file based on a key
    extract_value() {
      local key=$1
      local valuesFile=$2
      grep "^${key}:" "$valuesFile" | sed "s/^${key}:\s*//" 2>/dev/null || echo ""
    }

    # Function to process values files and set variables
    process_values_files() {
      log "Looking for configuration in ${VALUES_DIR}"

      if [ -z "$APP_NAME" ]; then
        log "APP_NAME environment variable not set. Skipping configuration lookup."
        return
      fi

      for valuesFile in ${VALUES_DIR}/*-values.yaml; do
        if [ -f "$valuesFile" ]; then
          local baseName=$(basename "$valuesFile" "-values.yaml")
          if [[ "$APP_NAME" == "$baseName"* ]]; then
            EXTENSION=$(extract_value "extensions" "$valuesFile")
            FLAGS=$(extract_value "flags" "$valuesFile")
            JAVA_OPTS=$(extract_value "javaOpts" "$valuesFile")

            if [ -n "$EXTENSION" ]; then
              log "Using extensions from $valuesFile: $EXTENSION"
            fi

            if [ -n "$FLAGS" ]; then
              log "Using flags from $valuesFile: $FLAGS"
            fi

            if [ -n "$JAVA_OPTS" ]; then
              log "Using JVM options from $valuesFile: $JAVA_OPTS"
            fi

            return
          fi
        fi
      done

      log "No matching configuration found for APP_NAME: $APP_NAME"
    }

    # Function to start the WireMock server
    start_wiremock() {
      log "Starting WireMock server on port $PORT"

      if [ ! -f "$JAR_PATH" ]; then
        error "WireMock JAR not found at $JAR_PATH"
        exit 1
      fi

      # Build the command with JVM options
      local cmd="java"
      
      # Add JVM options if available
      [ -n "$JAVA_OPTS" ] && cmd="$cmd $JAVA_OPTS" && log "Starting with JVM options: $JAVA_OPTS"
      
      cmd="$cmd -cp $JAR_PATH wiremock.Run --port $PORT --root-dir $ROOT_DIR --global-response-templating"

      # Add extensions if available
      [ -n "$EXTENSION" ] && cmd="$cmd --extensions $EXTENSION" && log "Starting with extensions: $EXTENSION"

      # Add flags if available
      [ -n "$FLAGS" ] && cmd="$cmd $FLAGS" && log "Starting with flags: $FLAGS"

      # Execute the command
      log "Executing: $cmd"
      exec $cmd
    }

    # Main script execution
    log "Initializing WireMock startup script"
    
    # Only set these variables if they're not already set by environment
    EXTENSION="${EXTENSION:-}"
    FLAGS="${FLAGS:-}"
    JAVA_OPTS="${JAVA_OPTS:-}"
    
    # Only process values files if env vars are not set
    if [ -z "$JAVA_OPTS" ] && [ -z "$FLAGS" ]; then
      process_values_files
    else
      log "Using environment variables instead of config files"
    fi
    
    start_wiremock