import tarfile, os, glob, sys

tar_path = sys.argv[1]
tmp = sys.argv[2]
t = tarfile.open(tar_path)
t.extractall(tmp)
files = [f for f in glob.glob(os.path.join(tmp, "botd", "**", "*"), recursive=True) if os.path.isfile(f)]
print("extracted files:", len(files))
print("has pyc/cache:", any(f.endswith(".pyc") or "__pycache__" in f for f in files))
print("has install.sh:", os.path.isfile(os.path.join(tmp, "botd", "install.sh")))
import py_compile
bad = 0
for f in files:
    if f.endswith(".py"):
        try:
            py_compile.compile(f, doraise=True)
        except py_compile.PyCompileError as exc:
            bad += 1
            print("compile fail:", exc)
print("all payload .py compile:", "OK" if bad == 0 else f"{bad} FAILED")
sys.exit(0 if bad == 0 else 1)
