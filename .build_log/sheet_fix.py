import io
R=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\java\com\jarvis\app\ui\chat\RolePickerSheet.kt"
s=io.open(R,encoding="utf-8").read()
old=(
'                    Text(\n'
'                        "Current",\n'
'                        style = MaterialTheme.typography.labelSmall,\n'
'                        color = MaterialTheme.colorScheme.primary,\n'
'                    )'
)
new=(
'                    Text(\n'
'                        stringResource(R.string.role_picker_current),\n'
'                        style = MaterialTheme.typography.labelSmall,\n'
'                        color = MaterialTheme.colorScheme.primary,\n'
'                    )'
)
c=s.count(old); assert c==1, f"{c}"
s=s.replace(old,new)
io.open(R,"w",encoding="utf-8",newline="\n").write(s)
print("SHEET CURRENT FIXED")
