package app.xreader.data.extension

/**
 * v1.0.6.2 (F1): the REAL novelight capture, trimmed to its
 * load-bearing bones — "Return of the Sword God-Rank Civil
 * Servant" (book id 402), captured live 2026-10-30 from
 * ~/workspace/xreader/fixtures-capture/novelight/:
 *
 *  - [BOOK_PAGE]: the book page's h1, description meta and
 *    `BOOK_ID` script, plus its chapter anchors VERBATIM —
 *    a "latest" block (760…751) over the full rendered list
 *    (760…711): 60 anchors, 50 unique chapters, exactly what
 *    the site's book page renders.
 *  - [PAGE_2_RESPONSE] / [PAGE_16_RESPONSE]: the chapter-
 *    pagination endpoint's answers VERBATIM ({"html": …}):
 *    page 2 = chapters 710…661, page 16 = chapters 9…1 —
 *    the short last page, ending at chapter 1 (id 178682).
 *
 * The full walk over real + synthesised middle pages must
 * land on 759 unique chapters: the book's numbering reaches
 * 760 with one number missing along the way — the test pins
 * the real arithmetic, not the round number.
 */
object NovelightCaptureSite {
    const val HOST = "novelight.net"
    const val BASE = "https://novelight.net"
    const val BOOK_URL = "$BASE/book/return-of-the-sword-god-rank-civil-servant"

    val BOOK_PAGE = """
<html><head>
<title>Return of the Sword God-Rank Civil Servant (Light Novel)</title>
<meta name="description" content="Read online &quot;Return of the Sword God-Rank Civil Servant (Light Novel)&quot; - ✒ Synopsys  Sword God, Ahn Suho.The greatest swordsman player in humanity was, surprisingly enough,…"/>
</head><body>
<h1>Return of the Sword God-Rank Civil Servant (Light Novel)</h1>
<script type="text/javascript">
    const BOOK_ID = "402"
</script>
<div class="chapters">
<a href="/book/chapter/346374" class="chapter ">
    <div class="title">
        760 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">29.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/346121" class="chapter ">
    <div class="title">
        759 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">28.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/345712" class="chapter ">
    <div class="title">
        758 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">27.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/345646" class="chapter ">
    <div class="title">
        757 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">26.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/345232" class="chapter ">
    <div class="title">
        756 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">25.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/345085" class="chapter ">
    <div class="title">
        755 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">24.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/344739" class="chapter ">
    <div class="title">
        754 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">23.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/344611" class="chapter ">
    <div class="title">
        753 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">22.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/344443" class="chapter ">
    <div class="title">
        752 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">21.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/344292" class="chapter ">
    <div class="title">
        751 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">20.10.2026</span>
        
    </div>
</a>
</div>
<div class="chapters">
<a href="/book/chapter/346374" class="chapter ">
    <div class="title">
        760 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">29.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/346121" class="chapter ">
    <div class="title">
        759 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">28.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/345712" class="chapter ">
    <div class="title">
        758 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">27.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/345646" class="chapter ">
    <div class="title">
        757 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">26.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/345232" class="chapter ">
    <div class="title">
        756 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">25.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/345085" class="chapter ">
    <div class="title">
        755 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">24.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/344739" class="chapter ">
    <div class="title">
        754 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">23.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/344611" class="chapter ">
    <div class="title">
        753 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">22.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/344443" class="chapter ">
    <div class="title">
        752 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">21.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/344292" class="chapter ">
    <div class="title">
        751 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">20.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/344037" class="chapter ">
    <div class="title">
        750 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">19.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/343847" class="chapter ">
    <div class="title">
        749 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">18.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/343681" class="chapter ">
    <div class="title">
        748 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">17.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/343475" class="chapter ">
    <div class="title">
        747 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">16.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/343296" class="chapter ">
    <div class="title">
        746 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">15.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/343149" class="chapter ">
    <div class="title">
        745 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">14.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/342894" class="chapter ">
    <div class="title">
        744 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">13.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/342763" class="chapter ">
    <div class="title">
        743 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">12.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/342609" class="chapter ">
    <div class="title">
        742 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">11.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/342380" class="chapter ">
    <div class="title">
        741 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">10.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/342184" class="chapter ">
    <div class="title">
        740 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">09.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/342062" class="chapter ">
    <div class="title">
        739 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">08.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/341822" class="chapter ">
    <div class="title">
        738 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">07.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/341587" class="chapter ">
    <div class="title">
        737 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">06.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/341391" class="chapter ">
    <div class="title">
        736 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">05.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/341255" class="chapter ">
    <div class="title">
        735 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">04.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/341094" class="chapter ">
    <div class="title">
        734 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">03.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/340907" class="chapter ">
    <div class="title">
        733 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">02.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/340611" class="chapter ">
    <div class="title">
        732 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">01.10.2026</span>
        
    </div>
</a>
<a href="/book/chapter/340468" class="chapter ">
    <div class="title">
        731 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">30.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/340265" class="chapter ">
    <div class="title">
        730 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">29.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/339902" class="chapter ">
    <div class="title">
        729 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">28.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/339732" class="chapter ">
    <div class="title">
        728 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">27.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/339464" class="chapter ">
    <div class="title">
        727 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">26.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/339227" class="chapter ">
    <div class="title">
        726 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">25.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/339042" class="chapter ">
    <div class="title">
        725 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">24.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/338684" class="chapter ">
    <div class="title">
        724 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">23.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/338459" class="chapter ">
    <div class="title">
        723 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">22.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/338321" class="chapter ">
    <div class="title">
        722 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">21.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/338056" class="chapter ">
    <div class="title">
        721 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">20.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/337895" class="chapter ">
    <div class="title">
        720 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">19.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/337619" class="chapter ">
    <div class="title">
        719 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">18.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/337387" class="chapter ">
    <div class="title">
        718 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">17.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/337220" class="chapter ">
    <div class="title">
        717 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">16.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/336972" class="chapter ">
    <div class="title">
        716 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">15.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/336818" class="chapter ">
    <div class="title">
        715 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">14.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/336658" class="chapter ">
    <div class="title">
        714 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">13.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/336657" class="chapter ">
    <div class="title">
        713 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">13.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/336208" class="chapter ">
    <div class="title">
        712 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">11.09.2026</span>
        
    </div>
</a>
<a href="/book/chapter/336018" class="chapter ">
    <div class="title">
        711 chapter 
    </div>
    <div class="chapter-info">
        
            
                <span class="cost"><i class="fa fa-lock"></i></span>
            

        

        <span class="author"><i class="fa fa-user"></i> KimSU</span>
        <span class="date">10.09.2026</span>
        
    </div>
</a>
</div>
</body></html>
    """.trimIndent()

    /** The pagination endpoint's page-2 answer, verbatim. */
    val PAGE_2_RESPONSE = """{"html": "\n<a href=\"/book/chapter/335760\" class=\"chapter \">\n    <div class=\"title\">\n        710 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">09.09.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/335551\" class=\"chapter \">\n    <div class=\"title\">\n        709 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">08.09.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/335759\" class=\"chapter \">\n    <div class=\"title\">\n        708 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">09.09.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/335117\" class=\"chapter \">\n    <div class=\"title\">\n        707 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">06.09.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/334972\" class=\"chapter \">\n    <div class=\"title\">\n        706 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">05.09.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/334648\" class=\"chapter \">\n    <div class=\"title\">\n        705 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">04.09.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/334647\" class=\"chapter \">\n    <div class=\"title\">\n        704 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">04.09.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/334225\" class=\"chapter \">\n    <div class=\"title\">\n        703 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">02.09.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/334059\" class=\"chapter \">\n    <div class=\"title\">\n        702 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">01.09.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/333918\" class=\"chapter \">\n    <div class=\"title\">\n        701 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">31.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/333674\" class=\"chapter \">\n    <div class=\"title\">\n        700 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">30.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/333537\" class=\"chapter \">\n    <div class=\"title\">\n        699 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">29.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/333395\" class=\"chapter \">\n    <div class=\"title\">\n        698 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">28.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/333182\" class=\"chapter \">\n    <div class=\"title\">\n        697 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">27.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/332984\" class=\"chapter \">\n    <div class=\"title\">\n        696 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">26.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/332760\" class=\"chapter \">\n    <div class=\"title\">\n        695 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">25.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/332557\" class=\"chapter \">\n    <div class=\"title\">\n        694 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">24.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/332345\" class=\"chapter \">\n    <div class=\"title\">\n        693 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">23.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/332086\" class=\"chapter \">\n    <div class=\"title\">\n        692 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">22.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/331904\" class=\"chapter \">\n    <div class=\"title\">\n        691 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">21.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/331625\" class=\"chapter \">\n    <div class=\"title\">\n        690 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">20.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/331516\" class=\"chapter \">\n    <div class=\"title\">\n        689 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">19.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/331283\" class=\"chapter \">\n    <div class=\"title\">\n        688 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">18.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/331031\" class=\"chapter \">\n    <div class=\"title\">\n        687 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">17.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/330854\" class=\"chapter \">\n    <div class=\"title\">\n        686 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">16.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/330706\" class=\"chapter \">\n    <div class=\"title\">\n        685 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">15.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/330458\" class=\"chapter \">\n    <div class=\"title\">\n        684 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">14.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/330196\" class=\"chapter \">\n    <div class=\"title\">\n        683 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">13.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/330087\" class=\"chapter \">\n    <div class=\"title\">\n        682 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">12.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/329769\" class=\"chapter \">\n    <div class=\"title\">\n        681 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">11.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/329632\" class=\"chapter \">\n    <div class=\"title\">\n        680 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">10.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/329420\" class=\"chapter \">\n    <div class=\"title\">\n        679 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">09.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/329105\" class=\"chapter \">\n    <div class=\"title\">\n        678 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n            \n                <span class=\"cost\"><i class=\"fa fa-lock\"></i></span>\n            \n\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">08.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/328824\" class=\"chapter \">\n    <div class=\"title\">\n        677 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">07.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/328382\" class=\"chapter \">\n    <div class=\"title\">\n        676 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">05.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/328246\" class=\"chapter \">\n    <div class=\"title\">\n        675 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">04.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/328044\" class=\"chapter \">\n    <div class=\"title\">\n        674 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">03.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/327873\" class=\"chapter \">\n    <div class=\"title\">\n        673 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">02.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/327641\" class=\"chapter \">\n    <div class=\"title\">\n        672 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">01.08.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/327501\" class=\"chapter \">\n    <div class=\"title\">\n        671 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">31.07.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/327310\" class=\"chapter \">\n    <div class=\"title\">\n        670 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">30.07.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/327309\" class=\"chapter \">\n    <div class=\"title\">\n        669 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">30.07.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/326655\" class=\"chapter \">\n    <div class=\"title\">\n        668 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">27.07.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/326478\" class=\"chapter \">\n    <div class=\"title\">\n        667 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">26.07.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/325873\" class=\"chapter \">\n    <div class=\"title\">\n        666 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">23.07.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/325722\" class=\"chapter \">\n    <div class=\"title\">\n        665 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">22.07.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/325601\" class=\"chapter \">\n    <div class=\"title\">\n        664 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">21.07.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/325254\" class=\"chapter \">\n    <div class=\"title\">\n        663 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">20.07.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/325044\" class=\"chapter \">\n    <div class=\"title\">\n        662 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">19.07.2026</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/324761\" class=\"chapter \">\n    <div class=\"title\">\n        661 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">17.07.2026</span>\n        \n    </div>\n</a>\n"}""".trimIndent()

    /** The pagination endpoint's page-16 answer, verbatim —
     * the short last page (9 rows, chapters 9…1). */
    val PAGE_16_RESPONSE = """{"html": "\n<a href=\"/book/chapter/179166\" class=\"chapter \">\n    <div class=\"title\">\n        9 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">09.11.2024</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/179165\" class=\"chapter \">\n    <div class=\"title\">\n        8 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">09.11.2024</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/179164\" class=\"chapter \">\n    <div class=\"title\">\n        7 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">09.11.2024</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/179163\" class=\"chapter \">\n    <div class=\"title\">\n        6 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">09.11.2024</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/178956\" class=\"chapter \">\n    <div class=\"title\">\n        5 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">08.11.2024</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/178955\" class=\"chapter \">\n    <div class=\"title\">\n        4 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">08.11.2024</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/178954\" class=\"chapter \">\n    <div class=\"title\">\n        3 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">08.11.2024</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/178953\" class=\"chapter \">\n    <div class=\"title\">\n        2 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> KimSU</span>\n        <span class=\"date\">08.11.2024</span>\n        \n    </div>\n</a>\n\n<a href=\"/book/chapter/178682\" class=\"chapter \">\n    <div class=\"title\">\n        1 chapter \n    </div>\n    <div class=\"chapter-info\">\n        \n\n        <span class=\"author\"><i class=\"fa fa-user\"></i> solee_79</span>\n        <span class=\"date\">07.11.2024</span>\n        \n    </div>\n</a>\n"}""".trimIndent()
}
