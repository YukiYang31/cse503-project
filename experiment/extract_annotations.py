#!/usr/bin/env python3
"""
Extract @Pure and @SideEffectFree annotations from JDK source files.

Parses each .java file to find Checker Framework annotated methods and builds
a canonical key for matching against the tool's SootUp-based output.

Output: experiment/ground_truth.json
"""

import json
import os
import re
import sys
from pathlib import Path

# Base directory of the JDK source
JDK_UTIL_DIR = Path("jdk/src/java.base/share/classes/java/util")

# Common java.lang types (auto-imported)
JAVA_LANG_TYPES = {
    "Object", "String", "Class", "Comparable", "Iterable", "Throwable",
    "Exception", "RuntimeException", "Error", "Number", "Integer", "Long",
    "Double", "Float", "Short", "Byte", "Character", "Boolean", "Void",
    "StringBuilder", "StringBuffer", "CharSequence", "Math", "System",
    "Cloneable", "Runnable", "Thread", "Process", "ProcessBuilder",
    "Enum", "Record", "StackTraceElement", "ClassLoader",
    "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface",
    "SafeVarargs", "AutoCloseable",
}

# Type variables: single uppercase letter, optionally followed by digits
TYPE_VAR_PATTERN = re.compile(r'^[A-Z]\d*$')

# Annotations we care about
TARGET_ANNOTATIONS = {"Pure", "SideEffectFree"}


def strip_comments_and_strings(source):
    """
    Remove all comments (// and /* */) and string/char literals from Java source,
    replacing them with spaces to preserve character positions.
    Returns the cleaned source with the same length.
    """
    result = list(source)
    i = 0
    n = len(source)
    while i < n:
        ch = source[i]
        if ch == '/' and i + 1 < n:
            if source[i + 1] == '/':
                # Line comment: blank until newline
                j = i
                while j < n and source[j] != '\n':
                    result[j] = ' '
                    j += 1
                i = j
                continue
            elif source[i + 1] == '*':
                # Block comment: blank until */
                j = i + 2
                result[i] = ' '
                result[i + 1] = ' '
                while j < n:
                    if source[j] == '*' and j + 1 < n and source[j + 1] == '/':
                        result[j] = ' '
                        result[j + 1] = ' '
                        j += 2
                        break
                    if source[j] != '\n':
                        result[j] = ' '
                    j += 1
                i = j
                continue
        elif ch == '"':
            # String literal
            result[i] = ' '
            i += 1
            while i < n and source[i] != '"':
                if source[i] == '\\' and i + 1 < n:
                    result[i] = ' '
                    i += 1
                if source[i] != '\n':
                    result[i] = ' '
                i += 1
            if i < n:
                result[i] = ' '
                i += 1
            continue
        elif ch == '\'':
            # Char literal
            result[i] = ' '
            i += 1
            while i < n and source[i] != '\'':
                if source[i] == '\\' and i + 1 < n:
                    result[i] = ' '
                    i += 1
                if source[i] != '\n':
                    result[i] = ' '
                i += 1
            if i < n:
                result[i] = ' '
                i += 1
            continue
        i += 1
    return ''.join(result)


def is_type_variable(name):
    """Check if a type name is a generic type variable (single uppercase letter, optionally + digit)."""
    return bool(TYPE_VAR_PATTERN.match(name))


def strip_annotations(text):
    """Remove all @Annotation tokens (possibly with parenthesized arguments) from text."""
    # Handle @Annotation(value) patterns first
    result = re.sub(r'@\w+(\.\w+)*\s*\([^)]*\)\s*', '', text)
    # Handle simple @Annotation patterns
    result = re.sub(r'@\w+(\.\w+)*\s*', '', result)
    return result.strip()


def erase_generics(type_str):
    """Remove generic type parameters: Collection<? extends E> -> Collection."""
    depth = 0
    result = []
    for ch in type_str:
        if ch == '<':
            depth += 1
        elif ch == '>':
            depth -= 1
        elif depth == 0:
            result.append(ch)
    return ''.join(result).strip()


def resolve_type(type_str, package_name, imports):
    """Resolve a simple type name to its fully qualified name."""
    type_str = type_str.strip()
    if not type_str:
        return type_str

    # Handle varargs: convert ... to []
    is_varargs = type_str.endswith('...')
    if is_varargs:
        type_str = type_str[:-3].strip()

    # Handle arrays
    array_suffix = ''
    while type_str.endswith('[]'):
        array_suffix += '[]'
        type_str = type_str[:-2].strip()
    if is_varargs:
        array_suffix += '[]'

    # Erase any remaining generics
    type_str = erase_generics(type_str)

    # Primitives
    if type_str in ('int', 'long', 'short', 'byte', 'char', 'boolean', 'float', 'double', 'void'):
        return type_str + array_suffix

    # Dotted name (inner class reference like Map.Entry)
    if '.' in type_str:
        parts = type_str.split('.')
        first = _resolve_simple(parts[0], package_name, imports)
        if len(parts) > 1:
            return first + '$' + '$'.join(parts[1:]) + array_suffix
        return first + array_suffix

    # Type variable -> java.lang.Object
    if is_type_variable(type_str):
        return 'java.lang.Object' + array_suffix

    return _resolve_simple(type_str, package_name, imports) + array_suffix


def _resolve_simple(name, package_name, imports):
    """Resolve a simple (non-array, non-generic) type name."""
    if name in imports:
        return imports[name]
    if name in JAVA_LANG_TYPES:
        return 'java.lang.' + name
    if is_type_variable(name):
        return 'java.lang.Object'
    if package_name:
        return package_name + '.' + name
    return name


def split_params(param_str):
    """Split parameter list by commas, respecting generic < > nesting."""
    params = []
    depth = 0
    current = []
    for ch in param_str:
        if ch == '<':
            depth += 1
            current.append(ch)
        elif ch == '>':
            depth -= 1
            current.append(ch)
        elif ch == ',' and depth == 0:
            params.append(''.join(current))
            current = []
        else:
            current.append(ch)
    if current:
        params.append(''.join(current))
    return params


def parse_method_params(param_str, package_name, imports):
    """Parse a method's parameter list into resolved type names."""
    param_str = param_str.strip()
    if not param_str:
        return []

    params = split_params(param_str)
    result = []
    for param in params:
        param = strip_annotations(param).strip()
        if not param:
            continue

        # Skip receiver parameter (ends with 'this')
        if re.search(r'\bthis\s*$', param):
            continue

        # Split into tokens — last token is the variable name
        tokens = param.split()
        if len(tokens) < 2:
            type_str = tokens[0] if tokens else param
        else:
            var_name = tokens[-1]
            type_str = ' '.join(tokens[:-1])
            # Handle C-style array decl: Object o[]
            if '[]' in var_name:
                type_str += '[]' * var_name.count('[]')

        type_str = erase_generics(type_str).strip()
        resolved = resolve_type(type_str, package_name, imports)
        result.append(resolved)

    return result


def find_class_spans(clean_lines):
    """
    Find all class/interface/enum declarations and their brace spans.
    Returns a list of (start_line, end_line, class_name, parent_idx_or_None)
    where line numbers are 0-indexed.

    Uses the cleaned (no comments/strings) source lines.
    """
    # First pass: find all class/interface/enum declaration lines and record
    # the brace depth at each line.
    brace_depth_at = []  # brace depth at the START of each line
    depth = 0
    for line in clean_lines:
        brace_depth_at.append(depth)
        for ch in line:
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1

    # Find class declarations
    class_pattern = re.compile(r'\b(?:class|interface|enum)\s+(\w+)')
    class_decls = []  # (line_idx, class_name, open_brace_depth, open_brace_line)
    for i, line in enumerate(clean_lines):
        m = class_pattern.search(line)
        if m:
            class_name = m.group(1)
            pos = m.end()
            found_brace_on_line = '{' in line[pos:]
            if found_brace_on_line:
                braces_before = 0
                for ch in line[:line.index('{', pos) + 1]:
                    if ch == '{':
                        braces_before += 1
                open_depth = brace_depth_at[i] + braces_before
                open_brace_line = i
            else:
                open_depth = None
                open_brace_line = None
                for j in range(i + 1, len(clean_lines)):
                    if '{' in clean_lines[j]:
                        d = brace_depth_at[j]
                        for ch in clean_lines[j]:
                            if ch == '{':
                                d += 1
                                break
                        open_depth = d
                        open_brace_line = j
                        break
            if open_depth is not None:
                class_decls.append((i, class_name, open_depth, open_brace_line))

    # Now find the closing } for each class: it's when depth drops below open_depth
    # We need to find the line where depth returns to (open_depth - 1)
    # Compute brace depth at end of each line
    depth = 0
    depth_at_end = []
    for line in clean_lines:
        for ch in line:
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
        depth_at_end.append(depth)

    class_spans = []
    for line_idx, class_name, open_depth, open_brace_line in class_decls:
        close_line = None
        target_depth = open_depth - 1
        # Start searching from the line AFTER the opening { line
        for j in range(open_brace_line + 1, len(clean_lines)):
            if depth_at_end[j] <= target_depth:
                close_line = j
                break
        if close_line is None:
            close_line = len(clean_lines) - 1
        class_spans.append((line_idx, close_line, class_name))

    # Determine parent-child relationships based on containment
    # Sort by start line, then by span length (larger first = parent first)
    class_spans.sort(key=lambda s: (s[0], -(s[1] - s[0])))

    # Build result with parent index
    result = []
    for i, (start, end, name) in enumerate(class_spans):
        parent_idx = None
        # Find the smallest enclosing class
        best_size = float('inf')
        for j, (ps, pe, pn) in enumerate(class_spans):
            if j == i:
                continue
            if ps <= start and pe >= end:
                size = pe - ps
                if size < best_size:
                    best_size = size
                    parent_idx = j
        result.append((start, end, name, parent_idx))

    return result


def get_fq_class_name(class_spans, class_idx, package_name):
    """Build the fully qualified class name with $ for inner classes."""
    if class_idx is None:
        return package_name

    # Build the chain from outermost to this class
    chain = []
    idx = class_idx
    while idx is not None:
        chain.append(class_spans[idx][2])  # class name
        idx = class_spans[idx][3]  # parent index
    chain.reverse()

    fq = '$'.join(chain)
    if package_name:
        fq = package_name + '.' + fq
    return fq


def find_enclosing_class(line_idx, class_spans):
    """Find the most specific (smallest) class that contains the given line."""
    best_idx = None
    best_size = float('inf')
    for i, (start, end, name, parent) in enumerate(class_spans):
        if start <= line_idx <= end:
            size = end - start
            if size < best_size:
                best_size = size
                best_idx = i
    return best_idx


def extract_method_after_annotation(lines, ann_line_idx):
    """
    Given the line index of an annotation, find the method declaration that follows.
    Returns (method_name, param_string, has_body, method_line_idx) or None.
    """
    # Skip additional annotations, blank lines, and comment lines after the annotation
    method_start = None
    for i in range(ann_line_idx + 1, min(ann_line_idx + 15, len(lines))):
        stripped = lines[i].strip()
        if not stripped:
            continue
        # Skip comment lines
        if stripped.startswith('//') or stripped.startswith('/*') or stripped.startswith('*'):
            continue
        # Skip pure annotation lines (annotation only, no method signature)
        # A line that starts with @ but also contains ( is likely a method with inline annotation
        if stripped.startswith('@'):
            cleaned = strip_annotations(stripped)
            if not cleaned or cleaned.startswith('//') or cleaned.startswith('/*'):
                continue
            # Has content after stripping annotations — likely a method declaration
        # This should be the start of the method declaration
        method_start = i
        break

    if method_start is None:
        return None

    # Gather the full declaration until we close the parameter parens
    full_decl = ''
    paren_depth = 0
    found_open = False
    for i in range(method_start, min(method_start + 20, len(lines))):
        full_decl += ' ' + lines[i]
        for ch in lines[i]:
            if ch == '(':
                paren_depth += 1
                found_open = True
            elif ch == ')':
                paren_depth -= 1
                if found_open and paren_depth == 0:
                    break
        if found_open and paren_depth == 0:
            break

    if not found_open:
        return None

    # Strip annotations from the full declaration
    clean = strip_annotations(full_decl)

    if '(' not in clean:
        return None

    # Extract the part before (
    paren_pos = clean.index('(')
    before_paren = clean[:paren_pos].strip()

    tokens = before_paren.split()
    if not tokens:
        return None

    method_name = tokens[-1]

    # Extract parameter string
    param_start = clean.index('(') + 1
    depth = 1
    param_end = param_start
    for i in range(param_start, len(clean)):
        if clean[i] == '(':
            depth += 1
        elif clean[i] == ')':
            depth -= 1
            if depth == 0:
                param_end = i
                break
    param_str = clean[param_start:param_end]

    # Determine if the method has a body
    # Look at the rest of the declaration after the closing )
    rest_after_paren = clean[param_end + 1:]
    # Also look at the original source lines
    has_body = False
    # Search forward from method_start for { or ;
    for i in range(method_start, min(method_start + 10, len(lines))):
        line = lines[i]
        for ch in line:
            if ch == '{':
                has_body = True
                break
            if ch == ';':
                # Check we're past the parameter list
                # Simple heuristic: if we haven't found the closing ) yet, skip
                pass
        if has_body:
            break

    # More accurate: check after closing paren
    # Rebuild from source to check
    check_text = ''
    paren_d = 0
    found_close = False
    for i in range(method_start, min(method_start + 20, len(lines))):
        for ch in lines[i]:
            if ch == '(':
                paren_d += 1
            elif ch == ')':
                paren_d -= 1
                if paren_d == 0:
                    found_close = True
            elif found_close:
                if ch == '{':
                    has_body = True
                    break
                elif ch == ';':
                    has_body = False
                    break
        if found_close and (has_body or ';' in lines[i]):
            break

    return method_name, param_str, has_body, method_start


def extract_all_methods(clean_lines, lines, class_spans, package_name, imports, filepath):
    """
    Extract ALL method declarations from a file, regardless of annotations.
    Returns a list of entries with annotation="" (unannotated).
    """
    # Keywords that look like method calls but aren't method declarations
    KEYWORDS = {
        'if', 'while', 'for', 'switch', 'catch', 'synchronized', 'try',
        'assert', 'throw', 'return', 'else', 'do', 'new', 'super', 'this',
    }

    # Pattern to find potential method declarations:
    # [modifiers] [type-params] [return-type] method-name(
    # We look for identifier( that's preceded by at least some declaration context
    METHOD_PATTERN = re.compile(
        r'(?:^|(?<=\s))'
        r'(\w+)\s*\(',
    )

    results = []

    for line_idx, clean_line in enumerate(clean_lines):
        # Skip lines outside any class
        class_idx = find_enclosing_class(line_idx, class_spans)
        if class_idx is None:
            continue

        # Skip lines that are class/interface/enum declarations themselves
        if re.search(r'\b(?:class|interface|enum)\s+\w+', clean_line):
            continue

        # Skip annotation-only lines
        stripped = clean_line.strip()
        if not stripped or stripped.startswith('@'):
            continue

        # Find all potential method name( patterns on this line
        for m in METHOD_PATTERN.finditer(clean_line):
            method_name = m.group(1)

            # Skip keywords
            if method_name in KEYWORDS:
                continue

            # Check that this looks like a declaration, not a call.
            # A declaration has modifiers/return-type before the method name.
            before = clean_line[:m.start()].strip()
            # Strip annotations from the before part
            before_clean = strip_annotations(before).strip()

            # Get the enclosing class's simple name
            enclosing_class_simple = class_spans[class_idx][2]

            # Constructor: method_name matches class name, no return type needed
            is_constructor = (method_name == enclosing_class_simple)

            if is_constructor:
                # Constructor: before should be empty or only modifiers
                if before_clean:
                    tokens = before_clean.split()
                    modifiers = {'public', 'protected', 'private', 'static', 'final',
                                 'abstract', 'synchronized', 'native', 'strictfp', 'default'}
                    # All tokens before constructor name should be modifiers or type params
                    non_mod = [t for t in tokens if t not in modifiers and not t.startswith('<')]
                    if non_mod:
                        # Has a return type → not a constructor, it's a regular method
                        # where the method name happens to match the class name
                        is_constructor = False
            else:
                # Regular method: must have at least a return type before the name
                if not before_clean:
                    continue
                tokens = before_clean.split()
                modifiers = {'public', 'protected', 'private', 'static', 'final',
                             'abstract', 'synchronized', 'native', 'strictfp', 'default'}
                # Strip modifiers and optional type params to find return type
                non_mod = [t for t in tokens if t not in modifiers and not t.startswith('<')]
                if not non_mod:
                    # Only modifiers, no return type → not a method declaration
                    continue

                # Check the return type looks like a type (not a random word)
                return_type_token = non_mod[-1]
                # Erase generics for checking
                rt_clean = erase_generics(return_type_token).strip()
                if not rt_clean:
                    continue
                # Must look like a type: starts with uppercase, or is a primitive, or has dots/arrays
                primitives = {'int', 'long', 'short', 'byte', 'char', 'boolean', 'float', 'double', 'void'}
                if not (rt_clean[0].isupper() or rt_clean in primitives or '.' in rt_clean or '[]' in rt_clean):
                    continue

            # Now extract the full parameter list (balance parens across lines)
            # Start from the opening paren position
            open_paren_col = m.end() - 1  # position of '('
            paren_depth = 0
            found_open = False
            full_decl = ''
            decl_end_line = line_idx

            for li in range(line_idx, min(line_idx + 20, len(lines))):
                full_decl += ' ' + lines[li]
                for ch in lines[li] if li > line_idx else lines[li][open_paren_col:]:
                    if ch == '(':
                        paren_depth += 1
                        found_open = True
                    elif ch == ')':
                        paren_depth -= 1
                        if found_open and paren_depth == 0:
                            decl_end_line = li
                            break
                if found_open and paren_depth == 0:
                    break

            if not found_open or paren_depth != 0:
                continue

            # Check what follows the closing paren: { means has body, ; means abstract/native
            has_body = False
            found_terminator = False
            paren_d = 0
            found_close = False
            for li in range(line_idx, min(decl_end_line + 5, len(lines))):
                for ch in (lines[li] if li > line_idx else lines[li][open_paren_col:]):
                    if ch == '(':
                        paren_d += 1
                    elif ch == ')':
                        paren_d -= 1
                        if paren_d == 0:
                            found_close = True
                    elif found_close:
                        if ch == '{':
                            has_body = True
                            found_terminator = True
                            break
                        elif ch == ';':
                            has_body = False
                            found_terminator = True
                            break
                if found_terminator:
                    break

            if not found_terminator:
                continue

            # Extract parameter string from the ORIGINAL source
            # Re-extract using original lines
            param_text = ''
            pd = 0
            collecting = False
            for li in range(line_idx, min(decl_end_line + 1, len(lines))):
                start_col = open_paren_col if li == line_idx else 0
                for ci, ch in enumerate(lines[li][start_col:], start_col):
                    if ch == '(':
                        pd += 1
                        if pd == 1:
                            collecting = True
                            continue
                    elif ch == ')':
                        pd -= 1
                        if pd == 0:
                            collecting = False
                            break
                    if collecting:
                        param_text += ch

            # Build the entry
            fq_class = get_fq_class_name(class_spans, class_idx, package_name)
            canonical_method = '<init>' if is_constructor else method_name

            param_types = parse_method_params(param_text, package_name, imports)

            param_key = ','.join(param_types)
            canonical_key = f"{fq_class}.{canonical_method}({param_key})"

            results.append({
                'file': filepath.name,
                'class_name': fq_class,
                'method_name': canonical_method,
                'params': param_types,
                'annotation': '',
                'has_body': has_body,
                'canonical_key': canonical_key,
                'source_line': line_idx + 1,
            })

            # Only take the first match per line
            break

    return results


def parse_file(filepath):
    """
    Parse a single .java file and extract all @Pure/@SideEffectFree annotated methods.
    If the file has any annotations, also extract all unannotated methods.
    """
    with open(filepath, 'r', encoding='utf-8') as f:
        source = f.read()

    lines = source.split('\n')
    clean_source = strip_comments_and_strings(source)
    clean_lines = clean_source.split('\n')

    # Extract package name
    package_name = ''
    for line in lines:
        m = re.match(r'^\s*package\s+([\w.]+)\s*;', line)
        if m:
            package_name = m.group(1)
            break

    # Extract imports
    imports = {}
    for line in lines:
        m = re.match(r'^\s*import\s+([\w.]+)\s*;', line)
        if m:
            fqn = m.group(1)
            simple = fqn.split('.')[-1]
            if not fqn.startswith('org.checkerframework'):
                imports[simple] = fqn

    # Find class spans using cleaned source
    class_spans = find_class_spans(clean_lines)

    # Scan for @Pure and @SideEffectFree annotations
    annotated_results = []
    i = 0
    while i < len(clean_lines):
        line = clean_lines[i].strip()

        # Check for target annotation
        ann_match = re.match(r'@(Pure|SideEffectFree)\b', line)
        if not ann_match:
            i += 1
            continue

        annotation = ann_match.group(1)

        # Find the method declaration using ORIGINAL lines (not cleaned)
        sig_info = extract_method_after_annotation(lines, i)
        if sig_info is None:
            i += 1
            continue

        method_name, param_str, has_body, method_line_idx = sig_info

        # Find enclosing class
        class_idx = find_enclosing_class(i, class_spans)
        fq_class = get_fq_class_name(class_spans, class_idx, package_name)

        # Determine if this is a constructor
        enclosing_class_simple = class_spans[class_idx][2] if class_idx is not None else ''
        is_constructor = (method_name == enclosing_class_simple)
        canonical_method = '<init>' if is_constructor else method_name

        # Parse and resolve parameters
        param_types = parse_method_params(param_str, package_name, imports)

        # Build canonical key
        param_key = ','.join(param_types)
        canonical_key = f"{fq_class}.{canonical_method}({param_key})"

        annotated_results.append({
            'file': filepath.name,
            'class_name': fq_class,
            'method_name': canonical_method,
            'params': param_types,
            'annotation': annotation,
            'has_body': has_body,
            'canonical_key': canonical_key,
            'source_line': method_line_idx + 1,
        })

        i = method_line_idx + 1 if method_line_idx > i else i + 1

    # If this file has annotations, also extract all unannotated methods
    if annotated_results:
        all_methods = extract_all_methods(
            clean_lines, lines, class_spans, package_name, imports, filepath
        )
        # Build set of canonical keys from annotated results
        annotated_keys = {r['canonical_key'] for r in annotated_results}

        # Add unannotated methods (not already in annotated set)
        for method in all_methods:
            if method['canonical_key'] not in annotated_keys:
                annotated_results.append(method)

    return annotated_results


def main():
    script_dir = Path(__file__).parent
    project_dir = script_dir.parent
    os.chdir(project_dir)

    util_dir = JDK_UTIL_DIR
    if not util_dir.is_dir():
        print(f"Error: JDK util directory not found: {util_dir}", file=sys.stderr)
        sys.exit(1)

    java_files = sorted(util_dir.glob("*.java"))
    print(f"Found {len(java_files)} .java files in {util_dir}")

    all_annotations = []
    files_with_annotations = 0
    for filepath in java_files:
        annotations = parse_file(filepath)
        if annotations:
            files_with_annotations += 1
        all_annotations.extend(annotations)

    # Summary
    pure_count = sum(1 for a in all_annotations if a['annotation'] == 'Pure')
    sef_count = sum(1 for a in all_annotations if a['annotation'] == 'SideEffectFree')
    unannotated_count = sum(1 for a in all_annotations if a['annotation'] == '')
    with_body = sum(1 for a in all_annotations if a['has_body'])
    without_body = sum(1 for a in all_annotations if not a['has_body'])

    print(f"\nFiles with annotations: {files_with_annotations}")
    print(f"Total methods extracted: {len(all_annotations)}")
    print(f"  @Pure: {pure_count}")
    print(f"  @SideEffectFree: {sef_count}")
    print(f"  Unannotated (implicitly side-effecting): {unannotated_count}")
    print(f"  With body (concrete): {with_body}")
    print(f"  Without body (abstract/interface): {without_body}")

    # Write output
    output_path = script_dir / "ground_truth.json"
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(all_annotations, f, indent=2)
    print(f"\nGround truth written to: {output_path}")

    # Print sample entries
    print("\nSample entries:")
    for entry in all_annotations[:10]:
        ann_str = f"@{entry['annotation']}" if entry['annotation'] else "(unannotated)"
        print(f"  {entry['canonical_key']}  {ann_str}  body={entry['has_body']}")


if __name__ == '__main__':
    main()
