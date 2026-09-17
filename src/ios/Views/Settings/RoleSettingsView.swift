// +--------------------------------------------------------------+
// | RoleSettingsView.swift - 角色设置页面                       |
// +--------------------------------------------------------------+

import SwiftUI

/// 角色设置页面
struct RoleSettingsView: View {
    @State private var showRoleList = false
    @State private var showRoleEditor = false
    @State private var editingRole: RoleFile? = nil
    @State private var showDeleteConfirm = false
    @State private var roleToDelete: RoleFile? = nil
    
    var body: some View {
        NavigationStack {
            List {
                // 当前角色卡片
                currentRoleCard
                
                // 角色列表
                Section {
                    ForEach(RoleStore.allRoles(), id: \.roleId) { role in
                        roleRow(role: role)
                    }
                    .onDelete(perform: confirmDelete)
                } header: {
                    Text("角色列表")
                }
                
                // 操作按钮
                Section {
                    Button(action: createNewRole) {
                        HStack {
                            Image(systemName: "plus.circle")
                            Text("添加角色")
                        }
                    }
                    
                    Button(action: resetToDefault) {
                        HStack {
                            Image(systemName: "arrow.uturn.down")
                            Text("重置为默认角色")
                        }
                    }
                }
            }
            .navigationTitle("角色")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: createNewRole) {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showRoleEditor) {
                RoleEditorView(role: $editingRole)
            }
            .confirmationDialog(
                "删除角色?",
                isPresented: $showDeleteConfirm,
                titleVisibility: .visible
            ) {
                if let role = roleToDelete, !role.metadata.isDefault {
                    Button("删除", role: .destructive) {
                        RoleStore.deleteRole(roleId: role.roleId)
                    }
                }
                Button("取消", role: .cancel) {}
            }
        }
    }
    
    // MARK: - 视图
    
    /// 当前激活的角色卡片
    private var currentRoleCard: some View {
        HStack {
            // 头像
            Image(uiImage: currentRoleAvatar)
                .resizable()
                .frame(width: 60, height: 60)
                .clipShape(Circle())
                .overlay {
                    Circle()
                        .stroke(Color.blue.opacity(0.3), lineWidth: 2)
                }
            
            VStack(alignment: .leading, spacing: 4) {
                Text("当前角色")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                
                Text(currentRoleName)
                    .font(.title3.weight(.semibold))
                
                Text(currentRoleStyle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            
            Spacer()
            
            // 切换按钮
            Button(action: showRoleList) {
                Image(systemName: "chevron.down")
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 4)
    }
    
    /// 单个角色行
    @ViewBuilder
    private func roleRow(role: RoleFile) -> some View {
        Button {
            if !role.metadata.isDefault {
                RoleStore.setCurrentRole(roleId: role.roleId)
            }
        } label: {
            HStack {
                // 头像
                if let avatarData = role.avatarData,
                   let uiImage = UIImage(data: avatarData) {
                    Image(uiImage: uiImage)
                        .resizable()
                        .frame(width: 40, height: 40)
                        .clipShape(Circle())
                } else {
                    Circle()
                        .fill(Color.blue.opacity(0.7))
                        .frame(width: 40, height: 40)
                        .overlay {
                            Text("✨")
                                .font(.title3)
                                .foregroundColor(.white)
                        }
                }
                
                VStack(alignment: .leading) {
                    Text(role.metadata.name)
                        .font(.body)
                        .strikethrough(role.metadata.isDefault, color: .secondary)
                    
                    if !role.metadata.style.isEmpty {
                        Text(role.metadata.style)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                
                Spacer()
                
                if role.metadata.isDefault {
                    Text("默认")
                        .font(.caption)
                        .foregroundStyle(.green)
                }
                
                Image(systemName: "ellipsis")
                    .foregroundStyle(.secondary)
            }
        }
        .swipeActions(edge: .trailing) {
            if !role.metadata.isDefault {
                Button("编辑", action: { editRole(role) })
                    .tint(.primary)
                Button("删除", action: { deleteRole(role) })
                    .tint(.red)
            }
        }
    }
    
    // MARK: - 私有方法
    
    private var currentRoleName: String {
        RoleStore.currentRole().metadata.name
    }
    
    private var currentRoleStyle: String {
        RoleStore.currentRole().metadata.style
    }
    
    private var currentRoleAvatar: UIImage {
        if let data = RoleStore.currentRole().avatarData,
           let image = UIImage(data: data) {
            return image
        }
        // 生成默认头像
        let size = CGSize(width: 64, height: 64)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { context in
            let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(),
                colors: [UIColor.systemBlue.cgColor, UIColor.darkBlue.cgColor] as CFArray,
                locations: [0, 1]
            )!
            context.cgContext.drawRadialGradient(
                gradient,
                startCenter: CGPoint(x: 32, y: 32),
                startRadius: 0,
                endCenter: CGPoint(x: 32, y: 32),
                endRadius: 32,
                options: []
            )
            "✨".draw(in: CGRect(x: 0, y: 0, width: 64, height: 64))
        }
    }
    
    private func createNewRole() {
        editingRole = nil
        showRoleEditor = true
    }
    
    private func editRole(_ role: RoleFile) {
        editingRole = role
        showRoleEditor = true
    }
    
    private func deleteRole(_ role: RoleFile) {
        roleToDelete = role
        showDeleteConfirm = true
    }
    
    private func confirmDelete(at offsets: IndexSet) {
        for index in offsets {
            let role = RoleStore.allRoles()[index]
            if !role.metadata.isDefault {
                roleToDelete = role
                showDeleteConfirm = true
            }
        }
    }
    
    private func resetToDefault() {
        RoleStore.resetToDefault()
    }
    
    private func showRoleList() {
        // 可以弹出角色选择列表
    }
}

// MARK: - 角色编辑器

struct RoleEditorView: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var role: RoleFile?
    
    @State private var name: String = ""
    @State private var style: String = ""
    @State private var lang: String = "auto"
    @State private var body: String = ""
    @State private var avatarImage: UIImage? = nil
    
    var body: some View {
        NavigationStack {
            Form {
                // 头像
                Section(header: Text("头像")) {
                    VStack {
                        Image(uiImage: avatarImage ?? currentDefaultAvatar)
                            .resizable()
                            .frame(width: 100, height: 100)
                            .clipShape(Circle())
                            .overlay {
                                Circle()
                                    .stroke(Color.gray.opacity(0.5), lineWidth: 2)
                            }
                            .onTapGesture(showImagePicker)
                        
                        Button(action: deleteAvatar) {
                            Text(avatarImage != nil ? "更换头像" : "添加头像")
                        }
                    }
                }
                
                // 身份
                Section(header: Text("身份")) {
                    TextField("角色名称", text: $name)
                    TextField("风格描述", text: $style, axis: .vertical)
                        .submitLabel(.done)
                    
                    Picker("语言", selection: $lang) {
                        Text("自动").tag("auto")
                        Text("中文").tag("zh")
                        Text("英文").tag("en")
                    }
                }
                
                // 人格
                Section(header: Text("人格/性格")) {
                    TextEditor(text: $body)
                        .frame(minHeight: 200)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(Color.gray.opacity(0.3))
                        )
                    
                    HStack {
                        Text("\(body.count) / 2000 字符")
                        Spacer()
                        if bodyCount > 1600 {
                            Text("⚠️ 超限")
                                .foregroundStyle(.red)
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(bodyCount > 1600 ? .red : .secondary)
                }
            }
            .navigationTitle(role == nil ? "添加角色" : "编辑角色")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { saveRole() }
                        .disabled(name.isEmpty || bodyCount > 2000)
                }
            }
            .onAppear {
                loadRoleData()
            }
        }
    }
    
    // MARK: - 视图计算
    
    private var currentDefaultAvatar: UIImage {
        let size = CGSize(width: 64, height: 64)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { context in
            let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(),
                colors: [UIColor.systemBlue.cgColor, UIColor.darkBlue.cgColor] as CFArray,
                locations: [0, 1]
            )!
            context.cgContext.drawRadialGradient(
                gradient,
                startCenter: CGPoint(x: 32, y: 32),
                startRadius: 0,
                endCenter: CGPoint(x: 32, y: 32),
                endRadius: 32,
                options: []
            )
            "✨".draw(in: CGRect(x: 0, y: 0, width: 64, height: 64))
        }
    }
    
    private var bodyCount: Int {
        body.count
    }
    
    // MARK: - 加载/保存
    
    private func loadRoleData() {
        if let role = role {
            name = role.metadata.name
            style = role.metadata.style
            lang = role.metadata.lang
            body = role.body
            avatarImage = role.avatarData.flatMap { UIImage(data: $0) }
        }
    }
    
    private func saveRole() {
        let metadata = RoleMetadata(name: name, style: style, lang: lang)
        let newRole = RoleFile(
            metadata: metadata,
            body: body,
            avatarData: avatarImage?.pngData(),
            roleId: role?.roleId ?? UUID().uuidString,
            isDefault: role?.metadata.isDefault ?? false
        )
        
        if let existingRole = role {
            // 编辑现有角色
            try? RoleStore.save(newRole)
        } else {
            // 创建新角色
            RoleStore.createRole(name: name, style: style, lang: lang, body: body, avatarData: avatarImage?.pngData())
        }
        
        NotificationCenter.default.post(name: .rolesChanged, object: nil)
        dismiss()
    }
    
    // MARK: - 头像图片选择
    
    @State private var showingImagePicker = false
    
    private func showImagePicker() {
        showingImagePicker = true
    }
    
    private func deleteAvatar() {
        avatarImage = nil
    }
}

// MARK: - 预览

struct RoleSettingsView_Previews: PreviewProvider {
    static var previews: some View {
        RoleSettingsView()
    }
}