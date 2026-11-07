import SwiftUI
import UserNotifications

private let accent = Color(red: 0.03, green: 0.42, blue: 0.33)

private enum HomeTab: Int, CaseIterable {
    case today, calendar, profile

    var title: String {
        switch self {
        case .today: return "今日打卡"
        case .calendar: return "日历"
        case .profile: return "我的"
        }
    }

    var icon: String {
        switch self {
        case .today: return "checkmark.circle"
        case .calendar: return "calendar"
        case .profile: return "person.crop.circle"
        }
    }
}

struct ContentView: View {
    @ObservedObject var reminders: ReminderCenter
    @SceneStorage("homeTab") private var selected = HomeTab.today

    var body: some View {
        TabView(selection: $selected) {
            TodayView(reminders: reminders)
                .tag(HomeTab.today)

            NavigationStack {
                ScrollView {
                    CheckInCalendar(today: reminders.today, history: reminders.history)
                        .padding(20)
                        .frame(maxWidth: 620)
                        .frame(maxWidth: .infinity)
                }
                .background {
                    ZStack {
                        Color(uiColor: .systemGroupedBackground)
                        RadialGradient(
                            colors: [Color.blue.opacity(0.14), .clear],
                            center: UnitPoint(x: 0.08, y: 0.62), startRadius: 0, endRadius: 330
                        )
                        RadialGradient(
                            colors: [Color.mint.opacity(0.24), .clear],
                            center: UnitPoint(x: 0.88, y: 0.13), startRadius: 0, endRadius: 350
                        )
                    }
                    .ignoresSafeArea()
                }
                .navigationTitle("日历")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar(.hidden, for: .tabBar)
            }
            .tag(HomeTab.calendar)

            ProfileView()
                .tag(HomeTab.profile)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            FloatingTabBar(selected: $selected)
        }
        .tint(accent)
    }
}

private struct FloatingTabBar: View {
    @Binding var selected: HomeTab
    @Namespace private var selection
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var activeColor: Color { colorScheme == .dark ? .mint : accent }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(HomeTab.allCases, id: \.self) { tab in
                let active = selected == tab
                Button {
                    withAnimation(reduceMotion ? nil : .spring(response: 0.32, dampingFraction: 0.82)) {
                        selected = tab
                    }
                } label: {
                    VStack(spacing: 2) {
                        Image(systemName: tab.icon)
                            .font(.system(size: 19, weight: active ? .semibold : .medium))
                            .accessibilityHidden(true)
                        Text(tab.title)
                            .font(.caption.weight(active ? .semibold : .medium))
                    }
                    .foregroundStyle(active ? activeColor : Color.secondary)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .padding(.vertical, 4)
                    .background {
                        if active {
                            Capsule()
                                .fill(activeColor.opacity(0.15))
                                .overlay {
                                    Capsule().strokeBorder(.white.opacity(0.65), lineWidth: 0.75)
                                }
                                .matchedGeometryEffect(id: "activeTab", in: selection)
                        }
                    }
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(active ? .isSelected : [])
            }
        }
        .padding(5)
        .frame(maxWidth: 380)
        .background { GlassSurface(shape: Capsule()) }
        .padding(.horizontal, 22)
        .padding(.top, 6)
        .padding(.bottom, 8)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("底部导航")
    }

}

private struct GlassSurface<SurfaceShape: InsettableShape>: View {
    let shape: SurfaceShape
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    var body: some View {
        if reduceTransparency {
            shape.fill(Color(uiColor: .secondarySystemBackground))
        } else {
#if compiler(>=6.2)
            if #available(iOS 26.0, *) {
                shape.fill(.clear).glassEffect(.regular, in: shape)
            } else {
                materialSurface
            }
#else
            materialSurface
#endif
        }
    }

    private var materialSurface: some View {
        shape.fill(.ultraThinMaterial)
            .overlay {
                shape.fill(
                    LinearGradient(colors: [.white.opacity(0.24), accent.opacity(0.08)],
                                   startPoint: .top, endPoint: .bottom)
                )
            }
            .overlay {
                shape.strokeBorder(
                    LinearGradient(colors: [.white.opacity(0.85), accent.opacity(0.20), .white.opacity(0.68)],
                                   startPoint: .top, endPoint: .bottom),
                    lineWidth: 1
                )
            }
            .shadow(color: .black.opacity(0.16), radius: 14, x: 0, y: 5)
    }
}

private struct TodayView: View {
    @ObservedObject var reminders: ReminderCenter
    @State private var editor: EditorRoute?
    @State private var deleting: CheckIn?

    private var completed: Int { reminders.items.filter { $0.completedDay == reminders.today }.count }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text(Date().formatted(.dateTime.month().day().weekday().locale(Locale(identifier: "zh_CN"))))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    progress
                    Text("每日事项").font(.headline).accessibilityAddTraits(.isHeader)
                    if reminders.items.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("添加第一项打卡").font(.headline)
                            Text("点右上角的 +，添加想每天完成的事项。").foregroundStyle(.secondary)
                        }
                        .card()
                    }
                    ForEach(reminders.items.sorted { $0.timeLabel < $1.timeLabel }) { item in
                        row(item)
                    }
                    if !reminders.items.isEmpty { notificationStatus }
                }
                .padding(.horizontal, 20)
                .padding(.top, 4)
                .padding(.bottom, 20)
                .frame(maxWidth: 620)
                .frame(maxWidth: .infinity)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("今日打卡")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.hidden, for: .tabBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("新建打卡", systemImage: "plus") { editor = EditorRoute() }
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("添加")
                }
            }
        }
        .sheet(item: $editor) { route in
            CheckInEditor(item: route.item) { item in
                Task { await reminders.save(item) }
            }
            .tint(accent)
        }
        .alert("删除打卡事项？", isPresented: Binding(
            get: { deleting != nil },
            set: { if !$0 { deleting = nil } }
        ), presenting: deleting) { item in
            Button("删除", role: .destructive) { Task { await reminders.delete(item.id) } }
            Button("取消", role: .cancel) {}
        } message: { item in
            Text("「\(item.title)」及其提醒和所有打卡记录都将被删除。")
        }
    }

    private var progress: some View {
        HStack(spacing: 12) {
            Text("今日进度").font(.caption.weight(.semibold))
            ProgressView(value: Double(completed), total: Double(max(reminders.items.count, 1)))
                .accessibilityHidden(true)
            Text("\(completed) / \(reminders.items.count)")
                .font(.subheadline.bold())
                .monospacedDigit()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var notificationStatus: some View {
        if reminders.authorization == .denied || reminders.authorization == .notDetermined {
            Button {
                Task { await reminders.requestNotifications() }
            } label: {
                Label("开启通知提醒", systemImage: "bell.badge")
            }
            .card()
        } else if reminders.scheduleFailed {
            Text("部分提醒排程失败。重新打开应用可重试。")
                .font(.footnote)
                .card()
        } else if let date = reminders.scheduledThrough {
            VStack(alignment: .leading, spacing: 5) {
                Text("已排入提醒队列至 \(date.formatted(.dateTime.month().day().hour().minute().locale(Locale(identifier: "zh_CN"))))")
                    .font(.subheadline.weight(.semibold))
                Text("iOS 队列容量有限。打开应用或操作通知后会补排，短间隔提醒可能只覆盖部分时段。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .card()
        }
    }

    private func row(_ item: CheckIn) -> some View {
        let done = item.completedDay == reminders.today
        return HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title).font(.subheadline.bold())
                Text("\(item.timeLabel) · 每 \(item.intervalLabel)提醒 · \(item.sound.label)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if done {
                Label("已打卡", systemImage: "checkmark.circle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(accent)
            } else {
                Button("确认打卡") {
                    let day = reminders.today
                    Task { await reminders.complete(item.id, day: day) }
                }
                .font(.subheadline.weight(.semibold))
            }

            Menu {
                Button("编辑", systemImage: "pencil") { editor = EditorRoute(item: item) }
                Button("删除", systemImage: "trash", role: .destructive) { deleting = item }
            } label: {
                Image(systemName: "ellipsis").frame(width: 44, height: 44)
            }
            .accessibilityLabel("管理\(item.title)")
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .padding(.vertical, 6)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct ProfileView: View {
    @AppStorage("profileNickname") private var nickname = ""
    @State private var draft = ""
    @State private var editing = false

    private var trimmedDraft: String { draft.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label(nickname.isEmpty ? "我的资料" : nickname, systemImage: "person.crop.circle")
                        .font(.headline)
                    Button("编辑昵称") {
                        draft = nickname
                        editing = true
                    }
                } header: {
                    Text("本机资料")
                } footer: {
                    Text("昵称和打卡记录保存在这台设备上。")
                }
                Section {
                    NavigationLink {
                        WidgetSettingsView()
                    } label: {
                        Label("桌面小部件", systemImage: "rectangle.grid.2x2")
                    }
                }
            }
            .navigationTitle("我的")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.hidden, for: .tabBar)
            .sheet(isPresented: $editing) {
                NavigationStack {
                    Form {
                        Section {
                            TextField("昵称", text: $draft)
                        } footer: {
                            Text("最多 30 个字符，留空使用默认名称")
                        }
                    }
                    .navigationTitle("编辑昵称")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("取消") { editing = false }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("保存") {
                                nickname = trimmedDraft
                                editing = false
                            }
                            .disabled(trimmedDraft.count > 30)
                        }
                    }
                }
            }
        }
    }
}

private struct WidgetSettingsView: View {
    var body: some View {
        List {
            Section("添加到桌面") {
                Text("1. 返回主屏幕，长按空白处。")
                Text("2. 点「编辑」→「添加小组件」。也可以点左上角的「＋」。")
                Text("3. 搜索 Daybyday，选择「今日打卡」和尺寸，然后点「添加小组件」。")
            }
            Section("使用小部件") {
                Text("桌面小部件显示今日进度。点事项旁的确认按钮即可打卡，事项较多时可用箭头翻页。")
            }
        }
        .navigationTitle("桌面小部件")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
    }
}

private struct CheckInCalendar: View {
    let today: String
    let history: CheckInHistory

    @State private var monthOffset = 0
    @State private var selected: Date?
    @Environment(\.colorScheme) private var colorScheme
    private var highlight: Color { colorScheme == .dark ? .mint : accent }
    private let weekdays = ["一", "二", "三", "四", "五", "六", "日"]
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 3), count: 7)

    private var calendar: Calendar { .current }
    private var currentMonth: Date {
        let now = Date()
        return calendar.dateInterval(of: .month, for: now)?.start ?? now
    }
    private var month: Date {
        calendar.date(byAdding: .month, value: monthOffset, to: currentMonth) ?? currentMonth
    }
    private var days: Range<Int> { calendar.range(of: .day, in: .month, for: month) ?? 1..<1 }
    private var leading: Int { (calendar.component(.weekday, from: month) + 5) % 7 }
    private var detailDate: Date? {
        selected ?? (monthOffset == 0 ? calendar.startOfDay(for: Date()) : nil)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                let year = calendar.component(.year, from: month)
                let monthNumber = calendar.component(.month, from: month)
                Text(String(format: "%d 年 %d 月", year, monthNumber))
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                monthButton("上月", icon: "chevron.left", step: -1)
                monthButton("下月", icon: "chevron.right", step: 1)
                    .disabled(monthOffset == 0)
            }
            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(weekdays, id: \.self) { weekday in
                    Text(weekday).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
                ForEach(0..<42) { cell in
                    let number = cell - leading + 1
                    if days.contains(number), let date = calendar.date(byAdding: .day, value: number - 1, to: month) {
                        dayCell(date, number: number)
                    } else {
                        Color.clear.frame(height: 41).accessibilityHidden(true)
                    }
                }
            }
            .contentShape(Rectangle())
            .simultaneousGesture(DragGesture(minimumDistance: 26).onEnded { value in
                let horizontal = value.translation.width
                guard abs(horizontal) > abs(value.translation.height) * 1.5, abs(horizontal) > 40 else { return }
                changeMonth(by: horizontal > 0 ? -1 : 1)
            })
            Rectangle()
                .fill(highlight.opacity(0.18))
                .frame(height: 1)
                .accessibilityHidden(true)

            let titles = detailDate.flatMap { history[CheckInStorage.dayKey($0)]?.values.sorted() } ?? []
            Text(detail(count: titles.count))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if !titles.isEmpty {
                CalendarTagLayout() {
                    ForEach(titles.indices, id: \.self) { index in
                        Text(titles[index])
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(Color.primary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(highlight.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
                            .overlay {
                                RoundedRectangle(cornerRadius: 12)
                                    .strokeBorder(.white.opacity(0.55), lineWidth: 0.75)
                            }
                    }
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background { GlassSurface(shape: RoundedRectangle(cornerRadius: 26)) }
        .onChange(of: today) { _, _ in selected = nil }
    }

    private func monthButton(_ label: String, icon: String, step: Int) -> some View {
        Button { changeMonth(by: step) } label: {
            Image(systemName: icon).font(.subheadline.bold()).frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .foregroundStyle(highlight)
        .accessibilityLabel(label)
    }

    private func changeMonth(by step: Int) {
        let next = monthOffset + step
        guard next <= 0, calendar.date(byAdding: .month, value: next, to: currentMonth) != nil else { return }
        monthOffset = next
        selected = nil
    }

    private func dayCell(_ date: Date, number: Int) -> some View {
        let key = CheckInStorage.dayKey(date)
        let count = history[key]?.count ?? 0
        let isToday = key == today
        let future = date > Date() && !isToday
        let isSelected = detailDate == date
        let state = future ? "未来日期" : (count > 0 ? "已打卡 \(count) 项" : "暂无打卡记录")
        return Button {
            selected = date
        } label: {
            VStack(spacing: 1) {
                Text(number.formatted(.number.grouping(.never))).font(.subheadline.weight(isToday ? .bold : .regular))
                if count > 0 {
                    Text("✓ \(count)").font(.caption2.weight(.semibold)).lineLimit(1)
                }
            }
            .foregroundStyle(isSelected ? Color.white : (isToday ? highlight : (future ? Color.secondary : Color.primary)))
            .frame(maxWidth: .infinity, minHeight: 41)
            .background {
                let shape = RoundedRectangle(cornerRadius: 10)
                if isSelected {
                    shape.fill(LinearGradient(
                        colors: [accent, Color(red: 0.07, green: 0.31, blue: 0.26)],
                        startPoint: .top, endPoint: .bottom
                    ))
                    .overlay { shape.strokeBorder(.white.opacity(0.44), lineWidth: 0.75) }
                } else if isToday || count > 0 {
                    shape.fill(highlight.opacity(0.16))
                        .overlay { shape.strokeBorder(.white.opacity(0.55), lineWidth: 0.75) }
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(future)
        .accessibilityLabel("\(dateLabel(date))，\(isToday ? "今天，" : "")\(state)")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func detail(count: Int) -> String {
        guard let date = detailDate else { return "有打卡的日期会标记数量，点击日期查看详情" }
        let label = dateLabel(date)
        if count == 0 { return "\(label) · 暂无打卡记录" }
        return "\(label) · 已打卡 \(count) 项"
    }

    private func dateLabel(_ date: Date) -> String {
        "\(calendar.component(.month, from: date)) 月 \(calendar.component(.day, from: date)) 日"
    }
}

private struct CalendarTagLayout: Layout {
    private let spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let frames = frames(for: subviews, width: proposal.width ?? .infinity)
        return CGSize(width: frames.map(\.maxX).max() ?? 0, height: frames.map(\.maxY).max() ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let frames = frames(for: subviews, width: proposal.width ?? bounds.width)
        for (subview, frame) in zip(subviews, frames) {
            subview.place(
                at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                anchor: .topLeading,
                proposal: ProposedViewSize(frame.size)
            )
        }
    }

    private func frames(for subviews: Subviews, width: CGFloat) -> [CGRect] {
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        let proposal = ProposedViewSize(width: width.isFinite ? width : nil, height: nil)
        return subviews.map { subview in
            let size = subview.sizeThatFits(proposal)
            if x > 0, x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            let frame = CGRect(origin: CGPoint(x: x, y: y), size: size)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            return frame
        }
    }
}

private struct EditorRoute: Identifiable {
    let id = UUID()
    var item: CheckIn?
}

private extension View {
    func card(background: Color = Color(uiColor: .secondarySystemGroupedBackground)) -> some View {
        padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(background, in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct CheckInEditor: View {
    let item: CheckIn?
    let onSave: (CheckIn) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var time: Date
    @State private var interval: String
    @State private var hours: Bool
    @State private var sound: ReminderSound

    init(item: CheckIn?, onSave: @escaping (CheckIn) -> Void) {
        self.item = item
        self.onSave = onSave
        let minutes = item?.interval ?? 30
        let isHours = minutes.isMultiple(of: 60)
        _title = State(initialValue: item?.title ?? "")
        _time = State(initialValue: Calendar.current.date(
            bySettingHour: item?.hour ?? 8, minute: item?.minute ?? 0, second: 0, of: Date()
        ) ?? Date())
        _interval = State(initialValue: String(isHours ? minutes / 60 : minutes))
        _hours = State(initialValue: isHours)
        _sound = State(initialValue: item?.sound ?? .system)
    }

    private var count: Int { Int(interval) ?? 0 }
    private var name: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form {
                Section("每日事项") {
                    TextField("事项名称，例如吃药", text: $title)
                    DatePicker("每天提醒时间", selection: $time, displayedComponents: .hourAndMinute)
                }
                Section("未打卡时重复提醒") {
                    TextField("间隔", text: $interval)
                        .keyboardType(.numberPad)
                        .accessibilityLabel("提醒间隔")
                    Picker("单位", selection: $hours) {
                        Text("分钟").tag(false)
                        Text("小时").tag(true)
                    }
                    .pickerStyle(.segmented)
                }
                Section("通知声音") {
                    Picker("提醒声音", selection: $sound) {
                        ForEach(ReminderSound.allCases) { Text($0.label).tag($0) }
                    }
                }
            }
            .navigationTitle(item == nil ? "新建打卡" : "编辑打卡")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        let parts = Calendar.current.dateComponents([.hour, .minute], from: time)
                        onSave(CheckIn(
                            id: item?.id ?? UUID(), title: name, hour: parts.hour ?? 8, minute: parts.minute ?? 0,
                            interval: hours ? count * 60 : count, sound: sound
                        ))
                        dismiss()
                    }
                    .disabled(name.isEmpty || name.count > 60 || !(1...(hours ? 23 : 1439)).contains(count))
                }
            }
        }
    }
}
