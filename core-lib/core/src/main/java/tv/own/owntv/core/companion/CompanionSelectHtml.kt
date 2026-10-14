package tv.own.owntv.core.companion

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Body renderer for the [CompanionMode.SELECT_ITEMS] pick page.
 *
 * The page used to be a single flat checklist, which fell apart as soon as a host sent hundreds of
 * rows: with 81 leagues, their teams and their players in one column, reaching one athlete meant
 * scrolling past several screens of other leagues. The picker is three levels deep now:
 *
 *  1. **`#/`** — the rows that have children, as tappable drill-down entries, plus "manage all";
 *  2. **`#/p/<id>`** — the children of one row, sub-grouped as tabs (Teams / Players);
 *  3. **`#/all`** — every top-level row as a checkbox, grouped and searchable.
 *
 * All levels live in one document and one `<form>`: every checkbox stays in the DOM (an inactive
 * level is merely `hidden`), so the single submit still carries the complete selection and
 * `POST /select` keeps its protocol. Navigation goes through `location.hash` so the browser's own
 * back gesture walks the levels, and the level-1 header keeps a Back button for the visible path.
 *
 * Deliberately free of `Context`: the caller resolves the labels (see [Labels]) and wraps the result
 * in `CompanionHtml`'s page shell, which keeps the level split, the grouping and the escaping
 * unit-testable on the JVM — the same split that [CompanionHttpProtocol] uses for the wire format.
 */
internal object CompanionSelectHtml {

    /** Every label the picker shows, already resolved from resources by `CompanionHtml`. */
    data class Labels(
        val description: String,
        val search: String,
        val save: String,
        val back: String,
        val manageAll: String,
        val all: String,
        val none: String,
        val empty: String,
    )

    /** Hash prefix of a level-2 view; what follows it is the parent row's id, percent-encoded. */
    private const val HASH_LEVEL2 = "#/p/"

    fun body(pin: String, title: String, items: List<CompanionSelectItem>, labels: Labels): String {
        val topLevel = items.filter { it.parentId == null }
        val childrenByParent = items.filter { it.parentId != null }.groupBy { it.parentId }
        // Level 1 answers "what can I configure here": only rows that actually have children (in the
        // Sports hub: the active leagues that have teams or duel players). A row without children has
        // nothing to drill into, so it lives in level 3 alone — where the checkboxes are.
        val parents = topLevel.filter { !childrenByParent[it.id].isNullOrEmpty() }
        return """
            <div class="picker">
              <form method="post" action="/select?pin=$pin" id="pick">
                <section class="lv" data-lv="1" id="lv1">
                  <header class="appbar"><h1 class="apptitle">${title.h()}</h1></header>
                  <p class="desc">${labels.description.h()}</p>
                  <div class="rows" id="rows1">
                    ${parents.joinToString("\n") { drillRow(it) }}
                  </div>
                  <p class="desc" id="lv1empty"${if (parents.isEmpty()) "" else " hidden"}>${labels.empty.h()}</p>
                  <a class="row" href="#/all">
                    <span class="grow">${labels.manageAll.h()}</span>
                    <span class="count" id="allcount">${topLevel.count { it.selected }}/${topLevel.size}</span>
                    <span class="chev">&rsaquo;</span>
                  </a>
                </section>
                ${parents.joinToString("\n") { level2(it, childrenByParent[it.id].orEmpty(), labels) }}
                <section class="lv" data-lv="3" id="lvall" hidden>
                  <header class="appbar">
                    <button type="button" class="back" data-back>&lsaquo; ${labels.back.h()}</button>
                    <span class="apptitle">${labels.manageAll.h()}</span>
                  </header>
                  <input type="search" class="q" autocomplete="off" placeholder="${labels.search.h()}" aria-label="${labels.search.h()}">
                  ${level3(topLevel, labels)}
                </section>
                <div class="savebar" id="savebar">
                  <span class="count" id="changed" aria-live="polite" hidden></span>
                  <button class="go" type="submit">${labels.save.h()}</button>
                </div>
              </form>
            </div>
            <script>$SCRIPT</script>
        """.trimIndent()
    }

    /** A level-1 entry: tapping it opens the row's children, the badge counts what is ticked there. */
    private fun drillRow(parent: CompanionSelectItem): String = """
        <a class="row" data-pid="${parent.id.h()}" href="$HASH_LEVEL2${parent.id.urlEncoded()}">
          <span class="grow">${parent.label.h()}</span>
          <span class="count" hidden></span>
          <span class="chev">&rsaquo;</span>
        </a>
    """.trimIndent()

    /**
     * Level 2: the children of one row. Their `group` splits them into tabs (rendered only when
     * there is more than one), each with a local search and the select-all / clear shortcuts.
     */
    private fun level2(parent: CompanionSelectItem, children: List<CompanionSelectItem>, labels: Labels): String {
        val groups = LinkedHashMap<String, MutableList<CompanionSelectItem>>()
        children.forEach { child ->
            groups.getOrPut(child.group.ifBlank { parent.label }) { ArrayList() }.add(child)
        }
        val tabs = if (groups.size < 2) "" else groups.keys.mapIndexed { index, groupLabel ->
            """<button type="button" class="tab${if (index == 0) " active" else ""}" data-k="g$index">${groupLabel.h()}</button>"""
        }.joinToString("").let { """<div class="tabs fit">$it</div>""" }
        val panels = groups.entries.mapIndexed { index, (_, rows) -> """
            <div class="panel${if (index == 0) " active" else ""}" data-k="g$index">
              <div class="quick">
                <button type="button" class="mini" data-set="on">${labels.all.h()}</button>
                <button type="button" class="mini" data-set="off">${labels.none.h()}</button>
              </div>
              <div class="rows">${rows.joinToString("\n") { checkRow(it) }}</div>
            </div>
        """.trimIndent() }.joinToString("\n")
        return """
            <section class="lv" data-lv="2" data-pid="${parent.id.h()}" hidden>
              <header class="appbar">
                <button type="button" class="back" data-back>&lsaquo; ${labels.back.h()}</button>
                <span class="apptitle">${parent.label.h()}</span>
              </header>
              <input type="search" class="q" autocomplete="off" placeholder="${labels.search.h()}" aria-label="${labels.search.h()}">
              $tabs
              $panels
            </section>
        """.trimIndent()
    }

    /**
     * Level 3: every top-level row as a checkbox, one collapsible block per group. A group with a
     * blank label is rendered without the block wrapper rather than as an empty heading.
     */
    private fun level3(topLevel: List<CompanionSelectItem>, labels: Labels): String {
        val groups = LinkedHashMap<String, MutableList<CompanionSelectItem>>()
        topLevel.forEach { item -> groups.getOrPut(item.group) { ArrayList() }.add(item) }
        return groups.entries.joinToString("\n") { (groupLabel, rows) ->
            val list = """<div class="rows">${rows.joinToString("\n") { checkRow(it) }}</div>"""
            if (groupLabel.isBlank()) list else """
                <details class="acc"${if (rows.any { it.selected }) " open" else ""}>
                  <summary>${groupLabel.h()}</summary>
                  $list
                </details>
            """.trimIndent()
        }
    }

    private fun checkRow(item: CompanionSelectItem): String =
        """<label class="check"><input type="checkbox" name="item" value="${item.id.h()}"${if (item.selected) " checked" else ""}><span>${item.label.h()}</span></label>"""

    /**
     * The whole behaviour of the page, in four short pieces: route by hash, filter the visible
     * level, toggle a level's tabs and shortcuts, and keep the badges honest. Plain ES5 on purpose —
     * a phone that reaches this page may be an old Android WebView, and the rest of the companion
     * pages are written the same way.
     */
    private val SCRIPT = """
        (function(){
          var P='$HASH_LEVEL2';
          var levels=[].slice.call(document.querySelectorAll('.lv'));
          var boxes=[].slice.call(document.querySelectorAll('input[name=item]'));
          var savebar=document.getElementById('savebar');
          var changed=document.getElementById('changed');
          var all=document.getElementById('lvall');
          var allcount=document.getElementById('allcount');
          var first=document.getElementById('lv1');
          function lvOf(pid){
            for(var i=0;i<levels.length;i++){ if(levels[i].getAttribute('data-pid')===pid) return levels[i]; }
            return null;
          }
          function levelOf(el){
            while(el&&el!==document){ if((' '+el.className+' ').indexOf(' lv ')>=0) return el; el=el.parentNode; }
            return null;
          }
          function panelOf(el){
            while(el&&el!==document){ if((' '+el.className+' ').indexOf(' panel ')>=0) return el; el=el.parentNode; }
            return null;
          }
          function show(target){
            for(var i=0;i<levels.length;i++){ levels[i].setAttribute('hidden',''); }
            if(target) target.removeAttribute('hidden');
            window.scrollTo(0,0);
          }
          function route(){
            var h=location.hash||'';
            if(h.indexOf(P)===0){
              var sec=lvOf(decodeURIComponent(h.slice(P.length)));
              if(sec){ show(sec); return; }
              // An unknown id (stale link): fall back to level 1 without leaving an entry behind.
              try{ if(history.replaceState) history.replaceState(null,'','#/'); }catch(e){}
              show(first); return;
            }
            if(h.indexOf('#/all')===0){ show(all); return; }
            show(first);
          }
          function fil(sec){
            var q=sec.querySelector('.q');
            var text=q?(q.value||'').trim().toLowerCase():'';
            var scope=sec.querySelector('.panel.active')||sec;
            var list=[].slice.call(scope.querySelectorAll('.check'));
            for(var i=0;i<list.length;i++){
              list[i].style.display=!text||list[i].textContent.toLowerCase().indexOf(text)>=0?'':'none';
            }
            var accs=[].slice.call(sec.querySelectorAll('details.acc'));
            for(var j=0;j<accs.length;j++){
              var d=accs[j], any=false, c=[].slice.call(d.querySelectorAll('.check'));
              for(var k=0;k<c.length;k++){ if(c[k].style.display!=='none') any=true; }
              d.style.display=any?'':'none';
              if(text) d.open=true;
            }
          }
          function refresh(){
            var n=0;
            for(var i=0;i<boxes.length;i++){ if(boxes[i].checked!==boxes[i].defaultChecked) n++; }
            if(changed){
              changed.textContent=n?'('+n+')':'';
              if(n) changed.removeAttribute('hidden'); else changed.setAttribute('hidden','');
            }
            if(savebar) savebar.className=n?'savebar dirty':'savebar';
            var rows=[].slice.call(document.querySelectorAll('#rows1 .row[data-pid]'));
            for(var r=0;r<rows.length;r++){
              var sec=lvOf(rows[r].getAttribute('data-pid'));
              var picked=sec?sec.querySelectorAll('input[name=item]:checked').length:0;
              var badge=rows[r].querySelector('.count');
              if(!badge) continue;
              if(picked){ badge.textContent=String(picked); badge.removeAttribute('hidden'); }
              else{ badge.setAttribute('hidden',''); }
            }
            if(allcount&&all){
              var total=0, pickedAll=0, allBoxes=[].slice.call(all.querySelectorAll('.check input'));
              for(var a=0;a<allBoxes.length;a++){ total++; if(allBoxes[a].checked) pickedAll++; }
              allcount.textContent=pickedAll+'/'+total;
            }
          }
          for(var b=0;b<boxes.length;b++){ boxes[b].addEventListener('change',refresh); }
          var tabBars=[].slice.call(document.querySelectorAll('.lv .tabs'));
          for(var t=0;t<tabBars.length;t++){
            (function(tabs){
              var list=[].slice.call(tabs.querySelectorAll('.tab'));
              for(var i=0;i<list.length;i++){
                (function(tab){
                  tab.addEventListener('click',function(){
                    var k=tab.getAttribute('data-k'), sec=levelOf(tabs);
                    for(var j=0;j<list.length;j++){ list[j].className=list[j]===tab?'tab active':'tab'; }
                    var panels=[].slice.call(sec.querySelectorAll('.panel'));
                    for(var p=0;p<panels.length;p++){
                      panels[p].className=panels[p].getAttribute('data-k')===k?'panel active':'panel';
                    }
                    fil(sec);
                  });
                })(list[i]);
              }
            })(tabBars[t]);
          }
          var queries=[].slice.call(document.querySelectorAll('.lv .q'));
          for(var q=0;q<queries.length;q++){
            (function(input){
              input.addEventListener('input',function(){ fil(levelOf(input)); });
              // Enter in a search box would otherwise submit the form and save mid-filter.
              input.addEventListener('keydown',function(ev){
                if(ev.key==='Enter'||ev.keyCode===13) ev.preventDefault();
              });
            })(queries[q]);
          }
          var setAll=[].slice.call(document.querySelectorAll('[data-set]'));
          for(var s=0;s<setAll.length;s++){
            (function(btn){
              btn.addEventListener('click',function(){
                var scope=panelOf(btn)||levelOf(btn), on=btn.getAttribute('data-set')==='on';
                var c=[].slice.call(scope.querySelectorAll('.check input'));
                for(var i=0;i<c.length;i++){ c[i].checked=on; }
                refresh();
              });
            })(setAll[s]);
          }
          var backs=[].slice.call(document.querySelectorAll('[data-back]'));
          for(var k2=0;k2<backs.length;k2++){
            backs[k2].addEventListener('click',function(){
              if(history.length>1) history.back(); else location.hash='#/';
            });
          }
          window.addEventListener('popstate',route);
          window.addEventListener('hashchange',route);
          if(location.hash&&location.hash!=='#/'&&history.replaceState){
            // Opened straight on a level (a reload keeps the hash): give level 1 its own history
            // entry underneath, so the Back button and the phone's back gesture land there.
            var deep=location.hash;
            try{ history.replaceState(null,'','#/'); history.pushState(null,'',deep); }catch(e){}
          }
          route();
          refresh();
        })();
    """.trimIndent()

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun String.h(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
