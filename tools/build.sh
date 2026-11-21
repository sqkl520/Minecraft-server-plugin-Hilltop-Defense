#!/usr/bin/env bash
# R-6: 构建包装脚本 —— 自动探测/切换 JDK 21 后调用 Maven。
#
# 为什么需要它：默认 shell 的 JAVA_HOME 指向 JDK 17，直接跑 mvn 会报
# 「无效的目标发行版: 21」。pom.xml 里的 maven-enforcer-plugin 会让这个错误
# 变清楚，但不会自动切 JDK；本脚本负责给出可执行路径。
#
# 用法（参数原样透传给 Maven，缺省参数等价于 clean package）：
#   ./tools/build.sh                         # = mvn clean package
#   ./tools/build.sh -o compile              # = mvn -o compile
#   ./tools/build.sh -o test-compile         # = mvn -o test-compile
#   ./tools/build.sh -DskipTests clean package
#
# 脚本位于 <仓库根>/tools/，启动时会把 cwd 切到仓库根，因此在任何目录下调用都可以。
#
# 环境支持：Git Bash / MSYS2（有 cygpath）与 WSL（有 wslpath）都可以，
# 两种环境的盘符路径写法不同（/d/... vs /mnt/d/...），脚本会自动识别并转换。
#
# 可覆盖的环境变量：
#   HV_JDK21_HOME   JDK 21 根目录（Windows 路径 C:\... 或当前 shell 的 unix 路径都可）
#   HV_JDK21_GLOB   JDK 21 探测 glob（默认按环境自动选 /d/Java/zulu21.48.17-* 或 /mnt/d/...）
#   HV_MAVEN        Maven 可执行文件路径（默认 D:/maven/apache-maven-3.9.9/bin/mvn.cmd）
#   HV_JAVA         Java 可执行文件名偏好（默认自动：优先 java.exe，其次 java）
#   MAVEN_OPTS      由调用者自行设置；本脚本不会覆盖

set -uo pipefail

# ---- 定位仓库根 -------------------------------------------------------------
# 本脚本位于 <仓库根>/tools/，Maven 必须在 pom.xml 所在目录运行。
# 用脚本自身位置反推根目录（而不是调用者的 cwd），这样从任何目录调用都能工作。
HV_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HV_REPO_ROOT="$(cd "$HV_SCRIPT_DIR/.." && pwd)"
if [[ ! -f "$HV_REPO_ROOT/pom.xml" ]]; then
    echo "[build.sh][错误] 未在 $HV_REPO_ROOT 找到 pom.xml，无法确定仓库根目录。" >&2
    echo "[build.sh][提示] 请保持脚本位于 <仓库根>/tools/build.sh，不要单独复制出去。" >&2
    exit 1
fi
cd "$HV_REPO_ROOT" || {
    echo "[build.sh][错误] 无法切换到仓库根目录：$HV_REPO_ROOT" >&2
    exit 1
}

# 让 Maven 的中文提示按 UTF-8 输出，避免 Windows 默认 GBK 下乱码。
# 不覆盖用户已有的 MAVEN_OPTS，只在后面追加缺失的编码参数。
for enc in -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8; do
    case " ${MAVEN_OPTS:-} " in
        *" $enc "*) ;;
        *) MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }$enc" ;;
    esac
done
export MAVEN_OPTS

MVN="${HV_MAVEN:-D:/maven/apache-maven-3.9.9/bin/mvn.cmd}"

# ---- 运行环境识别 ----------------------------------------------------------
# msys : Git Bash / MSYS2 / Cygwin —— 盘符写作 /d/...，有 cygpath
# wsl  : Windows Subsystem for Linux —— 盘符写作 /mnt/d/...，有 wslpath
# other: 其它 POSIX（Linux/macOS 原生）—— 不做盘符转换
if command -v cygpath >/dev/null 2>&1; then
    HV_ENV_KIND="msys"
elif command -v wslpath >/dev/null 2>&1 && { [[ -n "${WSL_DISTRO_NAME:-}" ]] || grep -qi microsoft /proc/version 2>/dev/null; }; then
    HV_ENV_KIND="wsl"
else
    HV_ENV_KIND="other"
fi

# 把 Windows 路径（C:\x 或 D:/x）转成当前 shell 可用的 unix 路径；
# 已经是 unix 路径的原样返回。
to_unix_path() {
    local p="$1"
    [[ -z "$p" ]] && return 0
    case "$p" in
        [A-Za-z]:[\\/]*)
            case "$HV_ENV_KIND" in
                msys) cygpath -u "$p" 2>/dev/null || printf '%s' "$p" ;;
                wsl)  wslpath -u "$p" 2>/dev/null || printf '%s' "$p" ;;
                *)    printf '%s' "$p" ;;
            esac
            ;;
        *) printf '%s' "$p" ;;
    esac
}

# 把 unix 路径转回 Windows 原生路径（供 JAVA_HOME 导出给 mvn.cmd 用）。
to_native_path() {
    local p="$1"
    [[ -z "$p" ]] && return 0
    case "$HV_ENV_KIND" in
        msys) cygpath -w "$p" 2>/dev/null || printf '%s' "$p" ;;
        wsl)  wslpath -w "$p" 2>/dev/null || printf '%s' "$p" ;;
        *)    printf '%s' "$p" ;;
    esac
}

# 默认探测 glob 随环境变化，否则在 WSL 下永远匹配不到。
default_jdk_glob() {
    case "$HV_ENV_KIND" in
        wsl) printf '%s' '/mnt/d/Java/zulu21.48.17-*' ;;
        *)   printf '%s' '/d/Java/zulu21.48.17-*' ;;
    esac
}
JDK21_GLOB="${HV_JDK21_GLOB:-$(default_jdk_glob)}"

# 读取 JDK 根目录下 java 可执行文件的版本首行，取主版本数字。
java_major() {
    local home="$1" java out ver
    home="$(to_unix_path "$home")"
    # WSL 下 Windows 的 java.exe 也能通过 interop 执行；两种都试。
    if [[ -n "${HV_JAVA:-}" && -x "$home/bin/${HV_JAVA}" ]]; then
        java="$home/bin/${HV_JAVA}"
    elif [[ -x "$home/bin/java.exe" ]]; then
        java="$home/bin/java.exe"
    elif [[ -x "$home/bin/java" ]]; then
        java="$home/bin/java"
    else
        return 1
    fi
    # 不用 `| head -n 1` 取首行：脚本开了 pipefail，而 Windows 的 java.exe 在
    # head 读完退出后会收到 SIGPIPE，管道返回 141，会被误判成「取版本失败」。
    # 改成整段捕获再用参数展开截首行，并对 CRLF 去掉行尾 \r。
    out="$("$java" -version 2>&1)" || return 1
    out="${out%%$'\n'*}"
    out="${out%$'\r'}"
    # 形如：java version "17.0.20.1" / openjdk version "21.0.10"
    ver="$(printf '%s' "$out" | sed -n 's/.*version "\([0-9][0-9.]*\)".*/\1/p')"
    if [[ "$ver" == 1.* ]]; then
        ver="$(printf '%s' "$ver" | cut -d. -f2)"   # 1.8 -> 8
    else
        ver="${ver%%.*}"
    fi
    [[ "$ver" =~ ^[0-9]+$ ]] || return 1
    printf '%s' "$ver"
}

is_jdk21() {
    [[ "$(java_major "$1" 2>/dev/null)" == "21" ]]
}

jdk21_home=""

# 1) JAVA_HOME 已经是 21 -> 直接用（用户显式意图优先）
if [[ -n "${JAVA_HOME:-}" ]] && is_jdk21 "$JAVA_HOME"; then
    jdk21_home="$JAVA_HOME"
    # 归一化成 Windows 原生路径，避免 unix 风格路径传给 mvn.cmd 时的兼容问题。
    export JAVA_HOME="$(to_native_path "$(to_unix_path "$jdk21_home")")"
    echo "[build.sh] JAVA_HOME 已是 JDK 21：$JAVA_HOME"
else
    # 2) 显式覆盖路径
    if [[ -n "${HV_JDK21_HOME:-}" ]]; then
        if is_jdk21 "$HV_JDK21_HOME"; then
            jdk21_home="$HV_JDK21_HOME"
            echo "[build.sh] 使用 HV_JDK21_HOME：$(to_unix_path "$HV_JDK21_HOME")"
        else
            echo "[build.sh][错误] HV_JDK21_HOME 指向的目录不是可用的 JDK 21：$HV_JDK21_HOME" >&2
            echo "[build.sh][提示] 该目录下应有 bin/java.exe（或 bin/java），且 java -version 主版本为 21。" >&2
            exit 1
        fi
    else
        # 3) glob 探测（按名称倒序，多个匹配时优先取较新的目录）
        shopt -s nullglob
        candidates=( $JDK21_GLOB )
        shopt -u nullglob
        if (( ${#candidates[@]} > 1 )); then
            IFS=$'\n' candidates=($(printf '%s\n' "${candidates[@]}" | sort -r)); unset IFS
        fi
        for c in "${candidates[@]}"; do
            if is_jdk21 "$c"; then
                jdk21_home="$c"
                echo "[build.sh] 探测到 JDK 21：$(to_unix_path "$c")"
                break
            fi
        done
    fi

    if [[ -z "$jdk21_home" ]]; then
        echo "[build.sh][错误] 未找到可用的 JDK 21，无法构建 HilltopVillage。" >&2
        echo "[build.sh] 当前 shell 环境：$HV_ENV_KIND${WSL_DISTRO_NAME:+（WSL: $WSL_DISTRO_NAME）}" >&2
        echo "[build.sh] 当前 JAVA_HOME=${JAVA_HOME:-<未设置>}$([[ -n "${JAVA_HOME:-}" ]] && printf '（主版本 %s）' "$(java_major "$JAVA_HOME" 2>/dev/null || echo '未知')")" >&2
        echo "[build.sh] 已探测路径：$JDK21_GLOB" >&2
        echo "[build.sh] 解决办法：export HV_JDK21_HOME=\"/path/to/jdk21\" 或 export JAVA_HOME=\"/path/to/jdk21\" 后重试。" >&2
        case "$HV_ENV_KIND" in
            wsl) echo "[build.sh] 本机参考路径：/mnt/d/Java/zulu21.48.17-ca-jdk21.0.10-win_x64（或 export HV_JDK21_HOME=/mnt/d/Java/zulu21.48.17-ca-jdk21.0.10-win_x64）" >&2 ;;
            *)   echo "[build.sh] 本机参考路径：D:\\Java\\zulu21.48.17-ca-jdk21.0.10-win_x64" >&2 ;;
        esac
        exit 1
    fi

    # 4) 转成 Windows 原生路径再导出：Maven 的 .cmd 在 Windows JVM 下更稳妥；
    #    PATH 前置 bin 目录，确保 mvn.cmd 内部调用的 java 也来自同一个 JDK。
    export JAVA_HOME="$(to_native_path "$(to_unix_path "$jdk21_home")")"
    export PATH="$(to_unix_path "$jdk21_home")/bin:$PATH"
fi

# mvn 可能是 .cmd（无 +x 位）或原生可执行文件，所以 -f 与 command -v 都判一次。
MVN_UNIX="$(to_unix_path "$MVN")"
if ! command -v "$MVN" >/dev/null 2>&1 && [[ ! -f "$MVN_UNIX" ]]; then
    echo "[build.sh][错误] 找不到 Maven：$MVN" >&2
    echo "[build.sh] 可用 HV_MAVEN=/path/to/mvn.cmd 覆盖。" >&2
    exit 1
fi

if (( $# == 0 )); then
    set -- clean package
fi

echo "[build.sh] JAVA_HOME=$JAVA_HOME"
echo "[build.sh] 执行：mvn $*"

# WSL 下不能直接 exec 一个 Windows 的 .cmd（会被当成 shell 脚本解析，报
# 「syntax error near unexpected token `('」），必须转交 cmd.exe；JAVA_HOME
# 则靠 WSLENV 让 WSL 自己转换成 Windows 路径后传给 Windows 进程。
case "$HV_ENV_KIND:$MVN" in
    wsl:*.[cC][mM][dD])
        # WSL 不能直接 exec Windows 的 .cmd（会被当 shell 脚本解析）。
        # 正确姿势是经 WSLENV 让 WSL 自己把 JAVA_HOME 转成 Windows 路径传过去：
        #   JAVA_HOME 保持在 WSL 里是 unix 路径，WSLENV="JAVA_HOME/p" 表示
        #   「传给 Windows 进程时做路径转换」——这样得到的是干净的 D:\... 。
        # 不要用 `cmd /c set JAVA_HOME=...` 拼字符串：转义与引号极易出错，
        # 而且拼错时 Maven 只会报 "JAVA_HOME is not defined correctly" 或
        # 悄悄退回系统 JDK 17。
        export JAVA_HOME="$(to_unix_path "$jdk21_home")"
        export WSLENV="${WSLENV:+$WSLENV:}JAVA_HOME/p"
        exec cmd.exe /c "$(to_native_path "$MVN_UNIX")" "$@"
        ;;
esac

if command -v "$MVN" >/dev/null 2>&1; then
    "$MVN" "$@"
else
    "$MVN_UNIX" "$@"
fi
exit $?
