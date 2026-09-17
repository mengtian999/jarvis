import io
p=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\java\com\jarvis\app\ui\chat\SessionMemorySheet.kt"
s=io.open(p,encoding="utf-8").read()
old = "val soulContent = soulRole?.body ?: \n"
new = "val soulContent = soulRole?.body ?: \"\"\n"
c=s.count(old); assert c==1, f"{c}"
s=s.replace(old,new)
io.open(p,"w",encoding="utf-8",newline="\n").write(s)
print("PATCHED")
