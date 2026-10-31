#!/usr/bin/env python3
import os
import sys
import json
import zipfile
from pathlib import Path

# Color codes
RED = '\033[0;31m'
GREEN = '\033[0;32m'
YELLOW = '\033[1;33m'
BLUE = '\033[0;34m'
NC = '\033[0m'

def main():
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except AttributeError:
        pass

    print(f"{BLUE}=== MIXIN CONFIGURATION VALIDATION ==={NC}\n")

    # Find jar in build/libs
    libs_dir = Path('build/libs')
    if not libs_dir.exists():
        print(f"{RED}ERROR: build/libs directory does not exist.{NC}")
        return 1

    jar_files = [f for f in libs_dir.glob('*.jar') if 'sources' not in f.name and 'original' not in f.name]
    if not jar_files:
        print(f"{RED}ERROR: No JAR file found in build/libs/{NC}")
        print("Run 'gradlew jar' first")
        return 1

    jar_file = jar_files[0]
    print(f"Validating JAR: {jar_file.name}")

    # Open the jar file
    try:
        with zipfile.ZipFile(jar_file, 'r') as jar:
            class_files = set(jar.namelist())
    except Exception as e:
        print(f"{RED}ERROR: Failed to read JAR file: {e}{NC}")
        return 1

    errors = 0
    total_mixins = 0

    # Find all mixin config files
    resources_dir = Path('src/main/resources')
    config_files = list(resources_dir.glob('**/*.mixins.json'))

    if not config_files:
        print(f"{YELLOW}WARNING: No mixin configuration files found{NC}")
        return 0

    for config_path in config_files:
        print(f"Checking {config_path.name}...")
        try:
            with open(config_path, 'r', encoding='utf-8') as f:
                config = json.load(f)
        except Exception as e:
            print(f"  {RED}✗ Invalid JSON syntax: {e}{NC}")
            errors += 1
            continue

        package = config.get('package')
        if not package:
            print(f"  {RED}✗ Missing package declaration{NC}")
            errors += 1
            continue

        package_path = package.replace('.', '/') + '/'

        for array_type in ['mixins', 'client', 'server']:
            mixins = config.get(array_type, [])
            if mixins:
                print(f"  Validating .{array_type}[]...")
                for mixin in mixins:
                    total_mixins += 1
                    class_path = package_path + mixin.replace('.', '/') + '.class'
                    
                    if class_path in class_files:
                        print(f"    {GREEN}✓{NC} {mixin}")
                    else:
                        print(f"    {RED}✗ {mixin}{NC}")
                        print(f"      Expected: {class_path}")
                        print(f"      Status: NOT FOUND IN JAR")
                        errors += 1

        print()

    print("=== VALIDATION SUMMARY ===")
    print(f"Total mixins checked: {total_mixins}")
    print(f"JAR file: {jar_file.name}")
    print(f"JAR size: {os.path.getsize(jar_file) / 1024:.1f} KB\n")

    if errors == 0:
        print(f"{GREEN}✓ ALL CHECKS PASSED{NC}")
        print("All mixin references are valid and present in JAR")
        return 0
    else:
        print(f"{RED}✗ VALIDATION FAILED{NC}")
        print(f"Found {errors} phantom mixin reference(s)\n")
        print("These mixins are declared in JSON configs but missing from the JAR.")
        print("This will cause ClassNotFoundException at runtime.\n")
        return 1

if __name__ == '__main__':
    sys.exit(main())
