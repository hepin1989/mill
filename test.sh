#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build & run tests using SBT
sbt core/test scalaplugin/test bin/test:assembly

# Build Mill using SBT
bin/target/mill devAssembly

# Secpmd build & run tests using Mill
out/devAssembly Core.test
out/devAssembly ScalaPlugin.test
out/devAssembly devAssembly