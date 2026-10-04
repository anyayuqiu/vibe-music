package com.vibemusic.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.vibemusic.mapper.ArtistMapper;
import com.vibemusic.mapper.SongMapper;
import com.vibemusic.mapper.StyleMapper;
import com.vibemusic.model.entity.Artist;
import com.vibemusic.model.entity.Song;
import com.vibemusic.model.entity.Style;
import com.vibemusic.model.vo.SongVO;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * VibeAgent 可以调用的音乐数据工具，让模型能够查询真实的曲库。
 */
@Component
public class MusicAgentTools {

    public record SongSummary(Long songId, String songName, String artistName, String album) {}
    public record SongCard(Long songId, String songName, String artistName, String album, String coverUrl) {}

    @Autowired
    private SongMapper songMapper;
    @Autowired
    private StyleMapper styleMapper;
    @Autowired
    private ArtistMapper artistMapper;

    /**
     * 按关键词搜索歌曲，关键词可以是歌名、歌手名或专辑名
     */
    @Tool("按关键词搜索歌曲，关键词可以是歌名、歌手名或专辑名")
    public List<SongSummary> searchSongs(String keyword) {
        return summarize(songMapper.searchSongsByKeyword(keyword, 10));
    }

    /**
     * 根据音乐风格推荐歌曲
     */
    @Tool("根据音乐风格推荐歌曲")
    public List<SongSummary> recommendSongsByStyle(String style) {
        QueryWrapper<Song> queryWrapper = new QueryWrapper<Song>()
                .like("style", style)
                .last("LIMIT 10");
        List<Song> songs = songMapper.selectList(queryWrapper);
        return summarize(fillArtistNames(songs));
    }

    /**
     * 获取平台的推荐歌曲（随机热门歌曲，20 首）
     */
    @Tool("获取平台的推荐歌曲")
    public List<SongSummary> getHotSongs() {
        return summarize(songMapper.getRandomSongsWithArtist());
    }

    /**
     * 获取平台支持的音乐风格列表
     */
    @Tool("获取平台支持的音乐风格名称列表")
    public List<String> listStyles() {
        return styleMapper.selectList(null).stream()
                .map(Style::getName)
                .toList();
    }

    /**
     * 为按风格查询出的歌曲补充歌手名
     */
    private List<SongVO> fillArtistNames(List<Song> songs) {
        if (songs.isEmpty()) {
            return List.of();
        }
        Set<Long> artistIds = songs.stream()
                .map(Song::getArtistId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> artistNameMap = artistIds.isEmpty() ? Map.of()
                : artistMapper.selectBatchIds(artistIds).stream()
                        .collect(Collectors.toMap(Artist::getArtistId, Artist::getArtistName));

        return songs.stream().map(song -> {
            SongVO vo = new SongVO();
            BeanUtils.copyProperties(song, vo);
            vo.setArtistName(artistNameMap.get(song.getArtistId()));
            return vo;
        }).toList();
    }

    private List<SongSummary> summarize(List<SongVO> songs) {
        return songs.stream().map(song -> new SongSummary(
                song.getSongId(), song.getSongName(), song.getArtistName(), song.getAlbum())).toList();
    }

    public List<SongCard> cardsForIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        List<Song> songs = songMapper.selectBatchIds(ids);
        Map<Long, Song> byId = songs.stream().collect(Collectors.toMap(Song::getSongId, song -> song));
        Set<Long> artistIds = songs.stream().map(Song::getArtistId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> artists = artistIds.isEmpty() ? Map.of() : artistMapper.selectBatchIds(artistIds).stream()
                .collect(Collectors.toMap(Artist::getArtistId, Artist::getArtistName));
        return ids.stream().distinct().limit(5).map(byId::get).filter(Objects::nonNull)
                .map(song -> new SongCard(song.getSongId(), song.getSongName(),
                        artists.get(song.getArtistId()), song.getAlbum(), song.getCoverUrl())).toList();
    }

}
