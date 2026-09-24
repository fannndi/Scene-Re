#!/bin/bash
# Verifies ShellEscape.quote by feeding it values that are adversarial to shells.
# Written as a file so no outer tool layer can interpolate the payloads first.

WORK=/mnt/c/Users/FANNNDI/AppData/Local/Temp/qtest_verify
OUT=$WORK/out.txt
INJ=$WORK/INJECTED
mkdir -p "$WORK"

# Literal translation of ShellEscape.quote:
#   "'" + value.replace("'", "'\\''") + "'"
quote() {
    local v=$1
    local out="'" 
    # replace every ' with  '\''   (quote, backslash, quote, quote)
    out+="${v//\'/\'\\\'\'}"
    printf "%s'" "$out"
}

pass=0; fail=0

check() {
    local v=$1
    local q
    q=$(quote "$v")
    rm -f "$OUT"
    # Exactly the command shape KernelProrp.setProp now produces.
    eval "chmod 664 $q 2>/dev/null; echo $q > \"$OUT\"" 2>/dev/null
    local got
    got=$(cat "$OUT" 2>/dev/null)
    if [ "$got" = "$v" ]; then
        printf 'PASS  in=<%s>\n' "$v"
        pass=$((pass+1))
    else
        printf 'FAIL  in=<%s>  quoted=<%s>  out=<%s>\n' "$v" "$q" "$got"
        fail=$((fail+1))
    fi
}

check 'plain'
check 'has space'
check "it's here"
check 'semi;colon'
check '$(whoami)'
check 'back`tick`'
check "a && rm -f $INJ && touch $INJ"
check 'quote"double"'
check 'wild*card'
check 'dollar$sign'
check "double''quote"
check 'star * glob'

echo
echo "passed=$pass failed=$fail"

rm -f "$INJ"
if [ -f "$INJ" ]; then
    echo "INJECTION: VULNERABLE"
else
    echo "INJECTION: neutralised (no artifact created)"
fi

echo
echo "--- proof of literalness: single-quoted vs bare ---"
rm -f "$OUT"
eval "echo \$(whoami) > \"$OUT\"" 2>/dev/null
echo "bare      echo \$(whoami)  -> $(cat "$OUT")   <- expansion happened"
q=$(quote '$(whoami)')
rm -f "$OUT"
eval "echo $q > \"$OUT\"" 2>/dev/null
echo "quoted    echo $q  -> $(cat "$OUT")   <- literal, correct"

# ---------------------------------------------------------------------------
# ShellEscape.cmd(): program name bare, every argument quoted.
#   fun cmd(program: String, vararg args: String) =
#       args.joinToString(" ", prefix = "$program ") { quote(it) }
# ---------------------------------------------------------------------------
cmd() {
    local program=$1; shift
    if [ $# -eq 0 ]; then printf '%s' "$program"; return; fi
    local out=$program
    local a
    for a in "$@"; do out+=" $(quote "$a")"; done
    printf '%s' "$out"
}

echo
echo "--- cmd(): arguments stay single tokens ---"

# Helper: run `cmd` against a fake "pm" that just prints its argv, one per line.
cat > "$WORK/fakepm" <<'FAKE'
#!/bin/bash
for a in "$@"; do echo "ARG<$a>"; done
FAKE
chmod +x "$WORK/fakepm"

cmd_check() {
    local desc=$1; shift
    rm -f "$OUT"
    # shellcheck disable=SC2086
    eval "$(cmd "$WORK/fakepm" "$@")" > "$OUT" 2>/dev/null
    local n
    n=$(wc -l < "$OUT" | tr -d ' ')
    if [ "$n" = "$#" ]; then
        printf 'PASS  %-28s argv=%s\n' "$desc" "$n"
        pass=$((pass+1))
    else
        printf 'FAIL  %-28s expected %s argv, got %s\n' "$desc" "$#" "$n"
        sed 's/^/        /' "$OUT"
        fail=$((fail+1))
    fi
}

cmd_check 'plain package'      com.foo.bar
cmd_check 'package with space' 'com.foo bar'
cmd_check "package with quote" "com.foo'bar"
cmd_check 'package + injection' "x; touch $INJ"
cmd_check 'package + && chain'  "x && touch $INJ"
cmd_check 'package + commandsub' 'pkg-$(whoami)'
cmd_check 'package + backtick'  'pkg-`whoami`'
cmd_check 'four-arg form'       pm suspend com.foo.bar

# ---------------------------------------------------------------------------
# cmdLine(): same as cmd, but rejects control characters outright.
#   fun cmdLine(program, vararg args) =
#       args.joinToString(" ", prefix = "$program ") {
#           if (isSingleLine(it)) quote(it) else "''" }
# ---------------------------------------------------------------------------
cmdline() {
    local program=$1; shift
    if [ $# -eq 0 ]; then printf '%s' "$program"; return; fi
    local out=$program
    local a
    for a in "$@"; do
        if [ "$(printf '%s' "$a" | tr -d '\000-\037\177')" = "$a" ]; then
            out+=" $(quote "$a")"
        else
            out+=" ''"
        fi
    done
    printf '%s' "$out"
}

cmdline_check() {
    local desc=$1; shift
    rm -f "$OUT"
    eval "$(cmdline "$WORK/fakepm" "$@")" > "$OUT" 2>/dev/null
    local n
    n=$(wc -l < "$OUT" | tr -d ' ')
    if [ "$n" = "$#" ]; then
        printf 'PASS  %-30s argv=%s (no extra line)\n' "$desc" "$n"
        pass=$((pass+1))
    else
        printf 'FAIL  %-30s expected %s argv, got %s\n' "$desc" "$#" "$n"
        sed 's/^/        /' "$OUT"
        fail=$((fail+1))
    fi
}

echo
echo "--- cmdLine(): control characters cannot add a line ---"
cmdline_check 'plain package'        com.foo.bar
cmdline_check 'newline in package'   "$(printf 'a\nb')"
cmdline_check 'CR in package'        "$(printf 'a\rb')"
cmdline_check 'tab in package'       "$(printf 'a\tb')"
cmdline_check 'NUL stripped upstream' 'com.foo.bar'
cmdline_check 'newline + injection'  "$(printf 'x\ntouch %s' "$INJ")"
cmdline_check 'quote + newline'      "$(printf "a'b\nc")"

echo
echo "--- contrast: cmd() preserves newline as one arg, cmdLine() collapses it ---"
rm -f "$OUT"
eval "$(cmd "$WORK/fakepm" "$(printf 'a\nb')")" > "$OUT" 2>/dev/null
printf 'cmd()     newline value -> %s argv  (newline preserved inside the arg)\n' "$(wc -l < "$OUT" | tr -d ' ')"
rm -f "$OUT"
eval "$(cmdline "$WORK/fakepm" "$(printf 'a\nb')")" > "$OUT" 2>/dev/null
printf 'cmdLine() newline value -> %s argv  (rejected -> empty arg)\n' "$(wc -l < "$OUT" | tr -d ' ')"

echo
echo "passed=$pass failed=$fail"

rm -f "$INJ"
if [ -f "$INJ" ]; then
    echo "INJECTION: VULNERABLE"
else
    echo "INJECTION: neutralised (no artifact created)"
fi
