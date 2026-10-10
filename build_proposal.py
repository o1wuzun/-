from docx import Document
from docx.shared import Pt
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

REFERENCE = r'C:\Users\ASUS\Downloads\201909280107-郭梦杰-临大校园微快递-赵德玉-开题报告.docx'
OUTPUT = r'F:\社区\算法学习目标驱动社区平台开题报告.docx'

doc = Document(REFERENCE)

def set_run(run, font='宋体', size=10.5, bold=False):
    run.font.name = font
    run._element.rPr.rFonts.set(qn('w:eastAsia'), font)
    run.font.size = Pt(size)
    run.bold = bold

def clear_cell(cell):
    for p in list(cell.paragraphs):
        p._element.getparent().remove(p._element)
    return cell.add_paragraph()

def fill_cell(cell, blocks, first_font='宋体'):
    clear_cell(cell)
    for idx, block in enumerate(blocks):
        p = cell.paragraphs[0] if idx == 0 else cell.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.LEFT
        p.paragraph_format.line_spacing = 1.5
        p.paragraph_format.space_after = Pt(0)
        if isinstance(block, tuple):
            text, font, bold = block
        else:
            text, font, bold = block, first_font, False
        r = p.add_run(text)
        set_run(r, font, 10.5, bold)

def fill_single(cell, text, align=WD_ALIGN_PARAGRAPH.CENTER, bold=False, font='宋体', size=10.5):
    clear_cell(cell)
    p = cell.paragraphs[0]
    p.alignment = align
    p.paragraph_format.line_spacing = 1.5
    r = p.add_run(text)
    set_run(r, font, size, bold)

# Title paragraph retained from reference document.
for p in doc.paragraphs:
    if p.text.strip():
        p.text = ''
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        r = p.add_run('临沂大学本科毕业论文（设计）开题报告')
        set_run(r, '黑体', 16, True)
        break

t = doc.tables[0]
# Metadata: intentionally blank name and student number as requested.
fill_single(t.cell(0, 0), '姓名', bold=False)
fill_single(t.cell(0, 1), ' ', font='宋体')
fill_single(t.cell(0, 2), '学号')
fill_single(t.cell(0, 3), ' ', font='宋体')
fill_single(t.cell(0, 4), '专业')
fill_single(t.cell(0, 5), '软件工程（校企合作）')
fill_single(t.cell(1, 0), '题目')
fill_single(t.cell(1, 1), '目标驱动的算法学习社区平台设计与实现', align=WD_ALIGN_PARAGRAPH.CENTER)

intro = [
    ('选题意义、研究现状及可行性分析', '黑体', True),
    '选题意义：',
    '随着在线编程教育、技术面试和算法竞赛的发展，越来越多的计算机学习者需要持续完成数据结构与算法训练。然而，现有题库和在线评测平台主要解决“提供题目”和“判断代码是否通过”，学习者仍然需要自行制定计划、记录过程、解释错误并维持学习习惯。很多用户能够找到学习资料，却难以把长期目标拆解为每日任务，也难以判断自己是否真正掌握某个知识点。',
    '本课题拟设计一个面向计算机学习者的目标驱动型社区平台。平台以学习目标为主线，将学习路径、每日任务、打卡、知识点、检查点、小组互助和学习 Agent 组织为连续流程。用户可以设定“校招算法准备”“动态规划专项”“零基础入门”等目标，系统根据阶段生成任务，并通过总结、变式题、代码提交记录和同伴反馈量化学习进度。项目的价值在于把单次做题行为转化为可追踪的学习过程，帮助用户形成可复用的个人学习认知，并通过小组交流提高坚持率。',
    '研究现状：',
    '目前 LeetCode、Codeforces、AtCoder、牛客等平台在题库、在线评测和竞赛方面较为成熟，但其核心交互通常围绕题目和提交结果展开；知乎、博客和即时通信群能够提供经验交流，却缺少与个人学习目标、检查点和阶段进度的统一关联。近年来，生成式人工智能开始用于编程教育、智能辅导和反馈生成，相关研究指出，大语言模型能够降低获取解释和反馈的门槛，但同时存在事实错误、幻觉、过度依赖和学习评价不稳定等风险[1-5]。因此，学习 Agent 不能只依赖模型生成答案，应优先检索用户自己的总结、错题、检查点结果和小组资料，在资料不足时再引用经授权的外部 OJ 数据、官方文档和高质量博客，并向用户展示来源、时间和置信度。',
    '可行性分析：',
    '经济可行性：本项目面向小规模用户群体，第一阶段采用模块化单体架构，使用关系型数据库保存学习目标、检查点、打卡和社区内容，使用 Redis 处理热点数据、限流和任务缓存，图片等媒体使用对象存储。系统初期不自建大规模题库和复杂在线判题集群，外部 OJ 通过可选适配器接入，能够控制开发和运行成本。',
    '技术可行性：系统采用 B/S 架构，前端负责页面交互和进度可视化，后端负责认证、目标管理、任务编排、检查点评估、社区内容、Agent 检索和外部数据同步。Codeforces 等 OJ 作为可选数据源，不接入时平台仍可依靠内部打卡和检查点运行。外部同步、通知、推荐和 Agent 索引均采用异步任务，结合缓存、幂等、限流、超时和降级机制，为高频访问提供保护。Agent 的知识总结评估采用结构化规则与模型协同方式，输出核心定义、适用条件、复杂度、缺失点、错误点和补救任务，避免把未经确认的推测写入用户长期认知。',
    '操作可行性：平台主要面向有明确学习目标的学生、求职者和编程爱好者，功能入口按照“目标—任务—检查—复盘—交流”组织，学习流程清晰。用户可选择是否接入 Codeforces、LeetCode 等外部平台，授权可以随时撤销。小组中的问题、经验和学习总结支持互评与引用，Agent 回答携带来源和置信度，用户可确认、纠错或停止参考相关资料。',
]
fill_cell(t.cell(2, 0), intro)

content = [
    ('研究内容、目标、拟采用的方法', '黑体', True),
    '研究内容：',
    '本系统主要研究目标驱动的学习计划管理、检查点量化评估、小组学习交流、基于检索的学习 Agent 和常用 OJ 数据适配五个方面。',
    '目标：',
    '1. 学习目标与进度模块：支持创建目标、选择学习阶段、设置截止日期和每日投入时间，生成学习路径，展示完成率、连续学习天数、阶段掌握度和风险状态。',
    '2. 每日任务与检查点模块：支持知识点学习、题目练习、代码或外部题目提交、每日总结和周复盘；检查点按照知识理解、实现能力、变式迁移和思路表达进行评估，失败后生成补救任务。',
    '3. 小组学习模块：支持按目标、水平、阶段和时间加入小组，发布问题、分享经验、提交错误复盘，其他成员可以从正确性、清晰度和实践价值等维度进行反馈。',
    '4. Agent 辅助学习模块：提供进度提醒、学习资料检索、总结评估、问题解释、外部链接推荐和个人认知库。Agent 默认检索平台内资料，外部资料仅在用户授权且本地证据不足或需要最新事实时使用。用户确认后才保存为有效认知，纠错采用版本化机制。',
    '5. OJ 数据适配模块：通过统一 Provider 接口接入 Codeforces 等常用 OJ，按用户授权同步题目元数据、提交记录、通过状态和 Rating 等必要信息，用于学习推送和进度校验；不同平台的权限、频率限制和数据字段差异由适配器隔离。',
    '拟采用的方法：',
    '1. 文献研究法：检索生成式人工智能教育应用、智能辅导、知识检索、编程学习分析和学习社区相关研究，为系统功能和风险控制提供依据。',
    '2. 需求分析与模块化设计：将系统划分为用户认证、目标计划、检查点、学习记录、小组交流、Agent、外部数据源和管理审核模块，采用模块化单体实现，保留后续拆分接口。',
    '3. 规则与模型协同评估：为每个知识点建立核心定义、适用条件、典型例题、复杂度和变式题检查项；Agent 根据结构化检查项输出反馈，并通过外部资料或 OJ 结果进行校验。',
    '4. 检索增强生成方法：建立个人学习资料索引，优先检索用户总结、提交记录、检查点反馈和小组高质量内容；回答保存来源、时间和置信度，不把无来源推测写入长期记忆。',
    '5. 事件驱动与异步处理：采用 outbox 事件和任务队列处理外部同步、通知、推荐候选生成、内容审核和 Agent 索引更新；使用缓存、游标分页、限流和降级策略保护高频接口。',
    '6. 测试与评价：通过功能测试、接口测试、并发访问测试和可用性测试验证系统；以目标完成率、检查点通过率、七日留存、任务按时完成率和 Agent 反馈采纳率评价平台效果。',
    '预期成果：完成一个可运行的算法学习社区平台原型，实现学习目标、每日任务、检查点、打卡、小组交流、Agent 检索辅助和至少一个 OJ 数据适配器，并形成系统设计文档、测试报告和毕业论文。',
]
fill_cell(t.cell(3, 0), content)

schedule = [
    ('进度安排', '黑体', True),
    '2026年7月—2026年8月：完成相关文献检索、需求分析和总体架构设计，明确学习目标、检查点、Agent 和 OJ 适配边界，完成开题报告。',
    '2026年9月—2026年10月：完成数据库设计、用户认证、学习目标、学习路径、每日任务、打卡和进度展示等基础模块。',
    '2026年11月—2026年12月：完成知识点、检查点、每日总结、小组学习、问题发布、经验分享和同伴评价模块。',
    '2027年1月—2027年2月：完成 Agent 检索辅助、个人学习认知版本管理、进度提醒和外部资料引用功能；完成 Codeforces 等至少一个 OJ 数据源的适配和异步同步。',
    '2027年3月：完成系统集成、接口测试、并发访问测试、安全检查和用户体验优化，修复主要问题。',
    '2027年4月—2027年5月：整理实验结果和系统文档，完成毕业论文撰写、修改、定稿和答辩准备。',
]
fill_cell(t.cell(4, 0), schedule)

refs = [
    ('主要参考文献', '黑体', True),
    '[1] UNESCO. Guidance for Generative AI in Education and Research[M]. Paris: UNESCO, 2023. ISBN: 978-92-3-100612-8.',
    '[2] Tlili A, Shehata B, Adarkwah M A, et al. What if the devil is my guardian angel: ChatGPT as a case study of using chatbots in education[J]. Smart Learning Environments, 2023, 10: 15. DOI:10.1186/s40561-023-00237-x.',
    '[3] Kasneci E, Sessler K, Küchemann S, et al. ChatGPT for good? On opportunities and challenges of large language models for education[J]. Learning and Individual Differences, 2023, 103: 102274. DOI:10.1016/j.lindif.2023.102274.',
    '[4] Ji Z, Lee N, Frieske R, et al. Survey of hallucination in natural language generation[J]. ACM Computing Surveys, 2023, 55(12): 248. DOI:10.1145/3571730.',
    '[5] NIST. Artificial Intelligence Risk Management Framework: Generative Artificial Intelligence Profile[R]. Gaithersburg: National Institute of Standards and Technology, 2024. DOI:10.6028/NIST.AI.600-1.',
    '[6] OWASP Foundation. OWASP Top 10 for Large Language Model Applications 2023[EB/OL]. 2023. https://owasp.org/www-project-top-10-for-large-language-model-applications/.',
    '[7] ISO. ISO/IEC 42001:2023 Information technology—Artificial intelligence—Management system[S]. Geneva: International Organization for Standardization, 2023.',
    '[8] Codeforces. Codeforces API Documentation[EB/OL]. https://codeforces.com/apiHelp. 访问日期：2026-10-06.',
]
fill_cell(t.cell(5, 0), refs)

# Leave approval sections blank but retain labels and formatting.
for row, label in [(6, '指导教师意见：'), (7, '专业负责人意见：'), (8, '学院意见：')]:
    if row == 6:
        fill_cell(t.cell(row, 0), [(label, '黑体', True), ' ', '指导教师：                                       ', '年   月   日'])
    elif row == 7:
        fill_cell(t.cell(row, 0), [(label, '黑体', True), ' ', '专业负责人：                               ', '年   月   日'])
    else:
        fill_cell(t.cell(row, 0), [(label, '黑体', True), ' ', '学院（公章）：', '年   月   日'])

# Remove accidental blank leading paragraph in cells caused by helper.
doc.core_properties.author = ''
doc.core_properties.last_modified_by = ''
doc.core_properties.title = '目标驱动的算法学习社区平台设计与实现开题报告'
doc.save(OUTPUT)
print(OUTPUT)
