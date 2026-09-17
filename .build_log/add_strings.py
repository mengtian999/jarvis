import io

def add(path, anchor, newline):
    s=io.open(path,encoding="utf-8").read()
    c=s.count(anchor); assert c==1, f"{path}:{c}"
    s=s.replace(anchor, anchor+newline)
    io.open(path,"w",encoding="utf-8",newline="\n").write(s)
    print("OK",path)

V=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values\strings.xml"
Z=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values-zh\strings.xml"

add(V, "    <string name=\"forward_confirm\">Forward</string>\n",
    "    <string name=\"forward_select_at_least_one\">Select at least one message to forward</string>\n")
add(Z, "    <string name=\"forward_confirm\">转发</string>\n",
    "    <string name=\"forward_select_at_least_one\">请至少选择一条消息进行转发</string>\n")
print("STRINGS DONE")
