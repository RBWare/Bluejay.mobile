package com.futo.platformplayer.states

import com.futo.platformplayer.constructs.Event1
import com.futo.platformplayer.stores.FragmentedStorage
import com.futo.platformplayer.stores.StringHashSetStorage
import com.futo.platformplayer.stores.StringStorage

class StateMeta {
    val hiddenVideos = FragmentedStorage.get<StringHashSetStorage>("hiddenVideos");
    val hiddenCreators = FragmentedStorage.get<StringHashSetStorage>("hiddenCreators");

    val lastCommentSection = FragmentedStorage.get<StringStorage>("defaultCommentSection");

    /** Emitted with the creator url when a creator is hidden, so feeds can drop its content right away. */
    val onCreatorHidden = Event1<String>();

    fun getLastCommentSection(): Int{
        return when(lastCommentSection.value){
            "Polycentric" -> 0;
            "Platform" -> 1;
            else -> 0
        }
    }
    fun setLastCommentSection(value: Int) {
        when(value) {
            0 -> lastCommentSection.setAndSave("Polycentric");
            1 -> lastCommentSection.setAndSave("Platform");
            else -> lastCommentSection.setAndSave("");
        }
    }

    fun isVideoHidden(videoUrl: String) : Boolean {
        return hiddenVideos.contains(videoUrl);
    }
    fun addHiddenVideo(videoUrl: String) {
        hiddenVideos.addDistinct(videoUrl);
        hiddenVideos.save();
    }
    fun removeHiddenVideo(videoUrl: String) {
        hiddenVideos.remove(videoUrl);
        hiddenVideos.save();
    }
    fun removeAllHiddenVideos() {
        hiddenVideos.removeAll();
        hiddenVideos.save();
    }


    fun isCreatorHidden(creatorUrl: String): Boolean {
        return hiddenCreators.contains(creatorUrl);
    }
    fun addHiddenCreator(creatorUrl: String) {
        hiddenCreators.addDistinct(creatorUrl);
        hiddenCreators.save();
        onCreatorHidden.emit(creatorUrl);
    }
    fun removeHiddenCreator(creatorUrl: String) {
        hiddenCreators.remove(creatorUrl);
        hiddenCreators.save();
    }
    fun removeAllHiddenCreators() {
        hiddenCreators.removeAll();
        hiddenCreators.save();
    }

    companion object {
        private var _instance : StateMeta? = null;
        val instance : StateMeta
            get(){
            if(_instance == null)
                _instance = StateMeta();
            return _instance!!;
        };

        fun finish() {
            _instance?.let {
                _instance = null;
            }
        }
    }
}