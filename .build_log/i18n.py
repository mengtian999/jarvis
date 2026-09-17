import io
V=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values\strings.xml"
Z=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values-zh\strings.xml"
R=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\java\com\jarvis\app\ui\chat\RolePickerSheet.kt"
def rpl(path,old,new,n):
    s=io.open(path,encoding="utf-8").read()
    c=s.count(old); assert c==1, f"{n}:{c}"
    s=s.replace(old,new)
    io.open(path,"w",encoding="utf-8",newline="\n").write(s)
    print("OK",n)
# strings: role_picker_current after chat_longpress_select
rpl(V,
"    <string name=\"chat_longpress_select\">Select</string>\n",
"    <string name=\"chat_longpress_select\">Select</string>\n    <string name=\"role_picker_current\">Current</string>\n","str-en")
rpl(Z,
"    <string name=\"chat_longpress_select\">选择</string>\n",
"    <string name=\"chat_longpress_select\">选择</string>\n    <string name=\"role_picker_current\">当前</string>\n","str-zh")
# RolePickerSheet: use stringResource instead of literal
rpl(R,
'                        Text(\n                            "Current",\n                            style = MaterialTheme.typography.labelSmall,\n                            color = MaterialTheme.colorScheme.primary,\n                        )',
'                        Text(\n                            stringResource(R.string.role_picker_current),\n                            style = MaterialTheme.typography.labelSmall,\n                            color = MaterialTheme.colorScheme.primary,\n                        )',"sheet-current")
print("I18N DONE")
