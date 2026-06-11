package org.zhzssp.memorandum.feature.pkm.controller;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import org.zhzssp.memorandum.core.service.NoteService;
import org.zhzssp.memorandum.entity.Link;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.NoteType;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.pkm.service.MarkdownRenderer;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;
import org.zhzssp.memorandum.repository.LinkRepository;
import org.zhzssp.memorandum.repository.NoteRepository;
import org.zhzssp.memorandum.repository.UserRepository;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 笔记 PKM 视图控制器。提供：
 *   - GET    /note               列表
 *   - GET    /note/new           新建（可携带 ?title= 用于双链跳转兜底）
 *   - GET    /note/{id}          查看（渲染 Markdown + 反链）
 *   - GET    /note/{id}/edit     编辑
 *   - POST   /note/save          保存（创建或更新）
 *   - POST   /note/preview       Markdown → HTML 预览（Ajax）
 *   - GET    /note/by-title/{t}  按标题跳转（命中→详情；未命中→以该标题创建）
 *
 * 旧 /note/list、/note/add（@RestController）保持原样不动；路径不冲突。
 */
@Controller
@RequestMapping("/note")
public class NoteViewController {

    private final NoteService noteService;
    private final NoteRepository noteRepository;
    private final LinkRepository linkRepository;
    private final UserRepository userRepository;
    private final MarkdownRenderer markdown;
    private final RagSearchService rag;

    public NoteViewController(NoteService noteService,
                              NoteRepository noteRepository,
                              LinkRepository linkRepository,
                              UserRepository userRepository,
                              MarkdownRenderer markdown,
                              RagSearchService rag) {
        this.noteService = noteService;
        this.noteRepository = noteRepository;
        this.linkRepository = linkRepository;
        this.userRepository = userRepository;
        this.markdown = markdown;
        this.rag = rag;
    }

    @GetMapping
    public String list(Model model, Principal principal) {
        User user = currentUser(principal);
        List<Note> notes = noteService.listVisibleByUser(user);
        model.addAttribute("notes", notes);
        model.addAttribute("tagCloud", buildTagCloud(notes));
        return "noteList";
    }

    /**
     * Stage 3：标签过滤页 —— 复用 noteList 模板，仅按 tag 过滤后注入。
     * URL 形如 /note/tag/spring，大小写不敏感（库内统一小写存储建议）。
     */
    @GetMapping("/tag/{tag}")
    public String byTag(@PathVariable("tag") String tag, Model model, Principal principal) {
        User user = currentUser(principal);
        List<Note> all = noteService.listVisibleByUser(user);
        String norm = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
        List<Note> filtered = all.stream()
                .filter(n -> n.getTags() != null && containsTag(n.getTags(), norm))
                .toList();
        model.addAttribute("notes", filtered);
        model.addAttribute("tagCloud", buildTagCloud(all));
        model.addAttribute("activeTag", norm);
        return "noteList";
    }

    /**
     * Stage 3：笔记列表搜索框入口 —— 走 RagSearchService 的 hybrid 通路。
     * 请求：GET /note/search?q=xxx&topK=8
     * 响应：JSON 数组，字段 [source, noteId, title, sourcePath, chunkIdx, content, score, reason]
     *
     * 说明：未登录由 Spring Security 拦截；空 q 返回空数组（避免误触全表 cosine）。
     */
    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
    @ResponseBody
    public List<Map<String, Object>> search(@RequestParam("q") String q,
                                            @RequestParam(value = "topK", required = false) Integer topK,
                                            Principal principal) {
        User user = currentUser(principal);
        if (q == null || q.isBlank()) return List.of();
        List<RagSearchService.Hit> hits = rag.search(user, q, topK);
        List<Map<String, Object>> out = new ArrayList<>(hits.size());
        for (RagSearchService.Hit h : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", h.source());
            row.put("score", Math.round(h.score() * 1000.0) / 1000.0);
            row.put("reason", h.reason());
            row.put("chunkIdx", h.chunkIdx());
            row.put("content", h.content());
            if ("NOTE".equals(h.source())) {
                row.put("noteId", h.noteId());
                noteRepository.findById(h.noteId())
                        .filter(n -> n.getUser() != null
                                && n.getUser().getId() != null
                                && n.getUser().getId().equals(user.getId()))
                        .ifPresent(n -> row.put("title", n.getTitle()));
            } else {
                row.put("sourcePath", h.sourcePath());
            }
            out.add(row);
        }
        return out;
    }

    @GetMapping("/new")
    public String createPage(@RequestParam(required = false) String title, Model model) {
        Note n = new Note();
        if (title != null && !title.isBlank()) n.setTitle(title);
        n.setType(NoteType.SCRATCH);
        model.addAttribute("note", n);
        model.addAttribute("isNew", true);
        return "noteEdit";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model, Principal principal) {
        User user = currentUser(principal);
        Note note = noteService.findByIdForUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("笔记不存在或无权访问"));

        model.addAttribute("note", note);
        model.addAttribute("html", markdown.render(note.getContent()));
        model.addAttribute("backlinks", findBacklinks(user, id));
        return "noteView";
    }

    @GetMapping("/{id}/edit")
    public String editPage(@PathVariable Long id, Model model, Principal principal) {
        User user = currentUser(principal);
        Note note = noteService.findByIdForUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("笔记不存在或无权访问"));
        model.addAttribute("note", note);
        model.addAttribute("isNew", false);
        return "noteEdit";
    }

    @PostMapping("/save")
    public RedirectView save(@RequestParam(required = false) Long id,
                             @RequestParam String title,
                             @RequestParam(required = false, defaultValue = "") String content,
                             @RequestParam(required = false) String type,
                             @RequestParam(required = false) String tags,
                             Principal principal) {
        User user = currentUser(principal);
        NoteType nt = parseType(type);
        Note saved;
        if (id == null) {
            saved = noteService.create(user, title, content, nt);
            // create 后追加 tags 字段（如有）
            if (tags != null && !tags.isBlank()) {
                saved = noteService.update(saved, null, null, null, tags);
            }
        } else {
            Note n = noteService.findByIdForUser(id, user)
                    .orElseThrow(() -> new IllegalArgumentException("笔记不存在或无权访问"));
            saved = noteService.update(n, title, content, nt, tags == null ? "" : tags);
        }
        return new RedirectView("/note/" + saved.getId());
    }

    /** Markdown 实时预览。前端通过 X-CSRF-TOKEN 携带令牌，无需 CSRF 豁免。 */
    @PostMapping(value = "/preview", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    @ResponseBody
    public String preview(@RequestBody Map<String, String> body) {
        return markdown.render(body == null ? "" : body.getOrDefault("content", ""));
    }

    /**
     * [[Title]] 双链跳转兜底：命中→详情；未命中→预填标题进新建页。
     */
    @GetMapping("/by-title/{title}")
    public RedirectView byTitle(@PathVariable("title") String title, Principal principal) {
        User user = currentUser(principal);
        return noteRepository.findFirstByUserAndTitle(user, title)
                .map(n -> new RedirectView("/note/" + n.getId()))
                .orElseGet(() -> new RedirectView(
                        "/note/new?title=" + URLEncoder.encode(title, StandardCharsets.UTF_8)));
    }

    // ---------- helpers ----------

    private User currentUser(Principal principal) {
        if (principal == null) throw new IllegalStateException("未登录");
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new IllegalStateException("用户不存在"));
    }

    /**
     * 反向链接：所有 NOTE→NOTE 指向当前笔记的源笔记，且仅本人。
     */
    private List<Note> findBacklinks(User user, Long noteId) {
        return linkRepository.findByTargetTypeAndTargetId(Link.LinkTargetType.NOTE, noteId).stream()
                .filter(l -> l.getSourceType() == Link.LinkSourceType.NOTE)
                .map(l -> noteRepository.findById(l.getSourceId()).orElse(null))
                .filter(Objects::nonNull)
                .filter(n -> n.getUser() != null
                        && n.getUser().getId() != null
                        && n.getUser().getId().equals(user.getId()))
                .toList();
    }

    private NoteType parseType(String s) {
        if (s == null || s.isBlank()) return NoteType.SCRATCH;
        try {
            NoteType t = NoteType.valueOf(s);
            // 普通页面禁止创建 AGENT_MEMO
            return t == NoteType.AGENT_MEMO ? NoteType.SCRATCH : t;
        } catch (IllegalArgumentException e) {
            return NoteType.SCRATCH;
        }
    }

    /**
     * Stage 3：构造标签云 —— 把所有 tags 字段（"a,b,c" 形式）扁平化、计数、按热度倒排。
     * 返回 LinkedHashMap 保证模板按热度遍历；忽略空字符串与超长（防御）。
     */
    private static List<Map<String, Object>> buildTagCloud(List<Note> notes) {
        Map<String, Integer> count = new TreeMap<>();
        for (Note n : notes) {
            if (n.getTags() == null) continue;
            for (String raw : n.getTags().split(",")) {
                String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
                if (t.isEmpty() || t.length() > 30) continue;
                count.merge(t, 1, Integer::sum);
            }
        }
        return count.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .<Map<String, Object>>map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tag", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();
    }

    /** 判断 tags 字段（逗号分隔）是否包含指定 tag（小写比较）。 */
    private static boolean containsTag(String tagsField, String target) {
        if (tagsField == null || target == null || target.isEmpty()) return false;
        for (String raw : tagsField.split(",")) {
            if (raw != null && raw.trim().toLowerCase(Locale.ROOT).equals(target)) return true;
        }
        return false;
    }
}
