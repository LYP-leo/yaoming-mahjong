package com.mahjong.domain;

public record Tile(String id,String suit,int rank,String label,boolean red) {
    public Tile(String id,String suit,int rank,String label){this(id,suit,rank,label,false);}
}
