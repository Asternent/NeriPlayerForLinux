.pragma library

function pad2(value) {
    return value < 10 ? "0" + value : "" + value
}

/** 评论时间：今天显示时分，今年显示月日，更早显示年月日。 */
function formatTime(ms) {
    const value = Number(ms)
    if (!value || value <= 0)
        return ""
    const date = new Date(value)
    const now = new Date()
    const sameDay = date.getFullYear() === now.getFullYear()
            && date.getMonth() === now.getMonth()
            && date.getDate() === now.getDate()
    if (sameDay)
        return pad2(date.getHours()) + ":" + pad2(date.getMinutes())
    if (date.getFullYear() === now.getFullYear())
        return (date.getMonth() + 1) + "-" + pad2(date.getDate())
    return date.getFullYear() + "-" + pad2(date.getMonth() + 1) + "-" + pad2(date.getDate())
}

/** 点赞 / 回复数：过万折算成「1.2万」，过亿折算成「1.2亿」。 */
function formatCount(value) {
    let count = Number(value)
    if (!count || count <= 0)
        return ""
    if (count >= 100000000)
        return (count / 100000000).toFixed(1).replace(/\.0$/, "") + "亿"
    if (count >= 10000)
        return (count / 10000).toFixed(1).replace(/\.0$/, "") + "万"
    return "" + count
}

/** 评论数文案（含「条」）。 */
function countText(value) {
    const text = formatCount(value)
    return text === "" ? "" : text + "条"
}
