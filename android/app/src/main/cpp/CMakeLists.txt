cmake_minimum_required(VERSION 3.22.1)
project(friday C)
# Friday's own native helpers (research/19): thread placement for Laya's passes.
add_library(friday SHARED affinity.c)
