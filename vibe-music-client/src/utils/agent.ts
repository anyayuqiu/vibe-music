/**
 * VibeAgent 相关的解析工具
 */

/** agent 推荐的一首歌曲（与后端 SongVO 对应） */
export interface VibeSong {
  songId: number
  songName: string
  artistName: string
  album: string
  coverUrl: string
}
