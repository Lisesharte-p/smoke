import pygame
import random


WINDOW_WIDTH = 800
WINDOW_HEIGHT = 600
WINDOW_SIZE = (WINDOW_WIDTH, WINDOW_HEIGHT)


FONT = 'gbk.ttf'
FONT_COLOR = ((18, 179, 24), (16, 217, 14), (226, 188, 107),
              (111, 111, 135), (59, 39, 15), (213, 160, 36),
              (220, 220, 220), (46, 46, 93))


# 显示位置
POS_X = 0
POS_Y = 0
POS = (POS_X, POS_Y)


SUN_POS = (10, 0)
CARD_POS = (90, 8)


CARD_SIZE = (51, 70)


GRASS_POS = (36, 78)
GRASS_SIZE = (80, 100)
GRASS_NUM = (9, 5)

# 位移误差
GROW_PLUSX = (5, 5, -15, 0, 0, 5, -10)
GROW_PLUSY = (10, 5, 8, 0, 40, 10, -30)


MINIGAMES_ZOMBIE_PLUSX = -75
MINIGAMES_ZOMBIE_PLUSY = -70
ZOMBIE_PLUSY = 30


FPS = 60

# 草地是否被占用
GRASS = []

"""
Code
0 Peashooter
1 Sunflower
2 Cherrybomb
3 Wallnut
4 Potatomine
5 Doublepeashooter
6 Chomper


0 NormalZombie
1 ConeheadZombie
2 BucketheadZombie
"""
ZOMBIE_SUN_COST = (50, 75, 125)
SUN_COST = (100, 50, 150, 50, 25, 200, 150)

FROZEN_TIME = (7.5, 7.5, 3.5, 30, 30, 7.5, 7.5)

PLANT_SIZE = ((71, 71), (73, 74), (112, 81), (100, 100), (75, 55), (73, 71), (130, 114))
ZOMBIE_SIZE = ((166, 144), (166, 144), (166, 144))

INDEX_Zombie = [22, 11, 20]

ZOMBIE_SPEED = 122.24 / FPS

BULLET_START_POS = (53, 11)
BULLET_SIZE = (28, 28)

# 按钮位置
ADVENTURE_BUTTON = (384, 72)
MINIGAMES_BUTTON = (385, 203)

ADVENTURE_TEXT = ((552, 90), (575, 92))
HELP_BUTTON = (650, 530)
QUIT_BUTTON = (731, 510)
OPTIONS_BUTTON = (561, 486)

PAUSE_BUTTON = ((680, -10), (716, -10), (762, -10))
PAUSE_BUTTON_ALPHA = (683, -9)

BACK_TO_GAME = (224, 438)
BACK_TO_GAME_ALPHA = (224, 440)

PAUSE_OPTION_DOWN = ((298, 350), (334, 350), (380, 350), (426, 350), (472, 350))
PAUSE_OPTION_DOWN_ALPHA = (298, 354)

PAUSE_OPTION_UP = ((298, 280), (334, 280), (380, 280), (426, 280), (472, 280))
PAUSE_OPTION_UP_ALPHA = (298, 284)

LOST_RESTART = ((295, 347), (331, 347), (377, 347), (423, 347), (469, 347))
LOST_RESTART_ALPHA = (295, 351)


# 音乐播放位置
MUSIC_POS = 0.0

MUSIC_VOLUME = 0.5
SOUND_VOLUME = 0.15

USEREVENT_COUNT = 0

LEVEL = 1
TOTAL_ZOMBIE = 37

INIT_SUN = 1500


class Button(object):
    def __init__(self, position, file, alpha=None):
        self.image = file
        self.image_alpha = alpha
        self.pos = position

    def draw(self):
        window.blit(self.image, self.pos)

    # 高亮显示
    def draw_alpha(self):
        window.blit(self.image_alpha, self.pos)


class Trophy(object):
    size = (166, 136)

    def __init__(self, posx, posy):
        self.posx = posx
        self.posy = posy
        self.endx = posx - 80
        self.endy = posy - 80
        falling_time = FPS * 2
        self.speed_x = (self.endx - posx) / falling_time
        self.speed_y = 0
        self.images = IMAGE_Trophy
        self.cur = 0
        self.FPSVar = 0
        self.index = 9

    def fall(self):
        if self.posy >= self.endy:
            return
        self.posx += self.speed_x
        self.posy += self.speed_y
        self.speed_y += 0.16

    def draw(self):
        window.blit(self.images[self.cur % self.index], (self.posx, self.posy))
        self.FPSVar = (self.FPSVar + 1) % 5
        if self.FPSVar == 0:
            self.cur += 1


class Head(object):
    def __init__(self, posx, posy):
        self.posx = random.randint(int(posx) + 20, int(posx) + 40)
        self.posy = posy - 30
        self.cur = 0
        self.FPSVar = 0
        self.images = IMAGE_Zombie_head

    def draw(self):
        window.blit(self.images[self.cur], (self.posx, self.posy))
        self.FPSVar = (self.FPSVar + 1) % 7
        if self.FPSVar == 0:
            self.cur += 1
        if self.cur == 17:
            head_list.remove(self)


class Body(object):
    def __init__(self, posx, posy, status, speed=None):
        self.posx = posx
        self.posy = posy
        self.cur = 0
        self.FPSVar = 0
        self.images = IMAGE_Zombie_body
        self.images_walking_dead = IMAGE_Zombie_body_walking
        self.images_eating_dead = IMAGE_Zombie_body_eating
        self.status = status
        self.speed = speed

    def draw(self):
        self.FPSVar = (self.FPSVar + 1) % 7
        if self.FPSVar == 0:
            self.cur += 1
        if self.status == 0:
            window.blit(self.images_walking_dead[self.cur], (self.posx, self.posy))
            self.move()
            if self.cur == 17:
                load_sound(random.randint(11, 12))
                self.cur = 0
                self.FPSVar = 0
                self.posx -= 10
                self.status = 2
        elif self.status == 1:
            window.blit(self.images_eating_dead[self.cur], (self.posx, self.posy))
            if self.cur == 10:
                load_sound(random.randint(11, 12))
                self.cur = 0
                self.FPSVar = 0
                self.posx -= 10
                self.status = 2
        if self.status == 2:
            window.blit(self.images[self.cur], (self.posx, self.posy))
            if self.cur == 16:
                body_list.remove(self)

    def move(self):
        if self.status == 0:
            self.posx -= self.speed

    def draw_walking(self):
        self.cur = 0

        self.FPSVar = (self.FPSVar + 1) % 7
        if self.FPSVar == 0:
            self.cur += 1
        if self.cur == 17:
            body_list.remove(self)

    def draw_eating(self):
        self.cur = 0
        window.blit(self.images_eating_dead[self.cur], (self.posx, self.posy))
        self.FPSVar = (self.FPSVar + 1) % 7
        if self.FPSVar == 0:
            self.cur += 1
        if self.cur == 17:
            body_list.remove(self)


class MinigamesZombie(object):
    def __init__(self, code, speed, health, images, move, initpos=None,
                 posx=None, grassy=None, cur=0, FPSVar=0, eating_flag=False):
        self.code = code
        self.index = INDEX_Zombie
        self.cur = cur
        self.FPSVar = FPSVar
        self.move = move
        self.images_normal = images[0]
        self.images_eating = images[1]
        self.images_boom = images[2]
        self.eating_flag = eating_flag
        self.alpha_image = self.images_normal[0].copy()
        self.alpha_image.set_alpha(100)
        self.speed = speed
        self.health = health

        if move:
            grass_x = (initpos[0] - GRASS_POS[0]) // GRASS_SIZE[0]
            grass_y = (initpos[1] - GRASS_POS[1]) // GRASS_SIZE[1] + 1
            self.posx = GRASS_POS[0] + grass_x * GRASS_SIZE[0] + MINIGAMES_ZOMBIE_PLUSX
            self.grassy = grass_y
            self.posy = self.grassy * GRASS_SIZE[1] + MINIGAMES_ZOMBIE_PLUSY

        if posx is not None:
            self.grassy = grassy
            self.posx = posx
            self.posy = self.grassy * GRASS_SIZE[1] + ZOMBIE_PLUSY

    def draw(self, pos):
        window.blit(self.images_normal[0], pos)

    def draw_on_grass(self):
        eat_flag = check_eat(self.posx, self.posy)

        if eat_flag is None:
            if self.eating_flag:
                self.cur = 0
                self.eating_flag = False
            pos = (self.posx, self.posy)
            window.blit(self.images_normal[self.cur % self.index[0]], pos)

            self.FPSVar = (self.FPSVar + 1) % 8
            if self.FPSVar == 0:
                self.cur += 1
                self.move_on_grass()
        else:
            if not self.eating_flag:
                self.cur = 0
                self.eating_flag = True
            pos = (self.posx, self.posy)
            window.blit(self.images_eating[self.cur % self.index[1]], pos)

            self.FPSVar = (self.FPSVar + 1) % 8
            if self.FPSVar == 0:
                self.cur += 1
                if self.cur % self.index[1] == 5:
                    eat_flag.hurt(70)
                    load_sound(random.randint(0, 1) * 2 + 1)

                elif self.cur % self.index[1] == 10:
                    eat_flag.hurt(70)
                    load_sound(2)

    def draw_alpha(self, pos):
        x = (pos[0] - GRASS_POS[0]) // GRASS_SIZE[0]
        y = (pos[1] - GRASS_POS[1]) // GRASS_SIZE[1]
        grow_pos = (GRASS_POS[0] + x * GRASS_SIZE[0] + MINIGAMES_ZOMBIE_PLUSX,
                    GRASS_POS[1] + y * GRASS_SIZE[1] + MINIGAMES_ZOMBIE_PLUSY + 20)
        window.blit(self.alpha_image, grow_pos)

    def move_on_grass(self):
        self.posx -= self.speed
        if self.posx < -150:
            zombie_list.remove(self)

    def hurt(self, num, status=None):
        self.health -= num
        if self.health <= 0:

            if status == 'mine':
                zombie_list.remove(self)
            else:
                self.die()

    def die(self):
        load_sound(10)
        head_list.append(Head(self.posx, self.posy))
        if self.eating_flag:
            body_list.append(Body(self.posx, self.posy, 1))
        else:
            body_list.append(Body(self.posx, self.posy, 0, self.speed / 8))
        zombie_list.remove(self)


class MinigamesNormalZombie(MinigamesZombie):
    def __init__(self, move, initpos=None, posx=None, grassy=None, cur=0, FPSVar=0, eating_flag=False):
        MinigamesZombie.__init__(self, 0, ZOMBIE_SPEED, 300, IMAGE_Normal_zombie, move, initpos,
                                 posx, grassy, cur, FPSVar, eating_flag)


class MinigamesConeZombie(MinigamesZombie):
    def __init__(self, move, initpos=None, posx=None, grassy=None):
        MinigamesZombie.__init__(self, 1, ZOMBIE_SPEED, 370, IMAGE_Conehead_zombie, move, initpos, posx=posx, grassy=grassy)

    def hurt(self, num, status=None):
        self.health -= num

        if self.health <= 0:

            if status == 'mine':
                zombie_list.remove(self)
            else:
                zombie_list.remove(self)
                zombie_list.append(MinigamesNormalZombie(True, posx=self.posx + 10, grassy=self.grassy, cur=self.cur,
                                                         FPSVar=self.FPSVar, eating_flag=self.eating_flag))


class MinigamesBucketZombie(MinigamesZombie):
    def __init__(self, move, initpos=None, posx=None, grassy=None):
        MinigamesZombie.__init__(self, 2, ZOMBIE_SPEED, 1100, IMAGE_Buckethead_zombie, move, initpos, posx=posx, grassy=grassy)

    def hurt(self, num, status=None):
        self.health -= num

        if self.health <= 0:

            if status == 'mine':
                zombie_list.remove(self)
            else:
                zombie_list.remove(self)
                zombie_list.append(MinigamesNormalZombie(True, posx=self.posx + 10, grassy=self.grassy, cur=self.cur,
                                                         FPSVar=self.FPSVar, eating_flag=self.eating_flag))


class MinigamesPlant(object):
    def __init__(self, code, index, FPSDiv, images, grassx, grassy):
        self.code = code
        self.index = index
        self.cur = 0
        self.FPSVar = 0
        self.FPSDiv = FPSDiv
        self.images = images
        self.health = 300
        self.grassx = grassx
        self.grassy = grassy

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        window.blit(self.images[self.cur % self.index], grow_pos)
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1

    def hurt(self, num):
        self.health -= num
        if self.health <= 0:
            self.die()

    def die(self):
        load_sound(30)
        plant_list.remove(self)


class MinigamesSunflower(MinigamesPlant):
    def __init__(self, grassx, grassy):
        MinigamesPlant.__init__(self, 1, 18, 5, IMAGE_Sunflower, grassx, grassy)

    def produce(self):
        sun_falling.append(Sun(self.grassx, self.grassy))

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        window.blit(self.images[self.cur % self.index], grow_pos)
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1

    def hurt(self, num):
        self.health -= num
        sun_falling.append(Sun(self.grassx, self.grassy))
        if self.health <= 0:
            self.die()


class MinigamesPeashooter(MinigamesPlant):
    def __init__(self, grassx, grassy):
        MinigamesPlant.__init__(self, 0, 13, 6, IMAGE_Peashooter, grassx, grassy)

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        window.blit(self.images[self.cur % self.index], grow_pos)
        return self.next()

    def next(self):
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1
        if self.cur % self.index == 2 and self.FPSVar == 0:
            if minigames_discover_zombie(self.grassx, self.grassy):
                return MinigamesBullet(self.grassx, self.grassy)
        return None


class MinigamesWallNut(MinigamesPlant):
    def __init__(self, grassx, grassy):
        MinigamesPlant.__init__(self, 3, 3, 6, IMAGE_Wallnut, grassx, grassy)
        self.health = 4000

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        if self.health >= 8000 / 3:
            window.blit(self.images[0], grow_pos)
        elif self.health >= 4000 / 3:
            window.blit(self.images[1], grow_pos)
        else:
            window.blit(self.images[2], grow_pos)


class MinigamesPotatoMine(MinigamesPlant):
    def __init__(self, grassx, grassy):
        MinigamesPlant.__init__(self, 4, 8, 10, IMAGE_Potatomine, grassx, grassy)
        self.initimage = IMAGE_Potatomine_init
        self.blowimage = IMAGE_Potatomine_blow
        self.ready = True
        self.blowing = False

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        if self.ready:
            zombie_to_blow = self.check_collision()
            if zombie_to_blow is not None:
                grow_pos_v = (grow_pos[0] - 30, grow_pos[1] - 25)
                window.blit(self.blowimage, grow_pos_v)
                self.ready = False
                self.blowing = True
                load_sound(23)
                zombie_to_blow.hurt(1800, 'mine')
                self.cur = 0
            else:
                window.blit(self.images[self.cur % self.index], grow_pos)
        else:
            if self.blowing:
                grow_pos_v = (grow_pos[0] - 40, grow_pos[1] - 60)
                window.blit(self.blowimage, grow_pos_v)
                if self.cur == 8:
                    plant_list.remove(self)
            else:
                grow_pos_v = (grow_pos[0] + 20, grow_pos[1] + 10)
                window.blit(self.initimage, grow_pos_v)
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1
            if not self.blowing and not self.ready and self.cur == 15 * FPS / self.FPSDiv:
                self.ready = True

    def check_collision(self):
        for each in zombie_list:
            temp_posx = GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code]
            if -90 <= each.posx - temp_posx <= -10 and each.grassy == self.grassy + 1:
                return each
        return None


class MinigamesDoublePea(MinigamesPeashooter):
    def __init__(self, grassx, grassy):
        MinigamesPeashooter.__init__(self, grassx, grassy)
        self.code = 5
        self.index = 15
        self.images = IMAGE_Doublepea


    def next(self):
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1
        if (self.cur % self.index == 2 or self.cur % self.index == 8) and self.FPSVar == 0:
            if minigames_discover_zombie(self.grassx, self.grassy):
                return MinigamesBullet(self.grassx, self.grassy)
        return None


class MinigamesChomper(MinigamesPlant):
    def __init__(self, grassx, grassy):
        MinigamesPlant.__init__(self, 6, (13, 9, 6), 8, IMAGE_Chomper, grassx, grassy)
        self.attackimages = IMAGE_Chomper_attack
        self.digestimages = IMAGE_Chomper_digest
        self.chewingtime = 42 * FPS / self.FPSDiv
        self.attack = False
        self.ready = True

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        if not self.attack:
            if self.ready:
                window.blit(self.images[self.cur % self.index[0]], grow_pos)
                if self.check_collision():
                    self.attack = True
                    self.cur = 0
            else:
                window.blit(self.digestimages[self.cur % self.index[2]], grow_pos)
                if self.cur == self.chewingtime:
                    self.cur = 0
                    self.ready = True
        else:
            window.blit(self.attackimages[self.cur], grow_pos)
            if self.cur == 6 and self.FPSVar == 0:
                load_sound(24)
                self.eat()
            if self.cur == self.index[1] - 1:
                self.cur = 0
                self.attack = False
                self.ready = False
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1

    def check_collision(self):
        for each in zombie_list:
            temp_posx = GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code]
            if -40 <= each.posx - temp_posx <= 80 and self.grassy == each.grassy - 1:
                return True

    def eat(self):
        for each in zombie_list:
            temp_posx = GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code]
            if -40 <= each.posx - temp_posx <= 80 and self.grassy == each.grassy - 1:
                each.hurt(1800, 'mine')
                return


class Zombie(object):
    def __init__(self, code, speed, health, images, posx=None, grassy=None, cur=0, FPSVar=0, eating_flag=False):
        self.code = code
        self.cur = cur
        self.FPSVar = FPSVar
        self.speed = speed
        self.health = health
        self.index = INDEX_Zombie
        if posx is None:
            self.grassy = random.randint(0, 4)
            self.posx = 730
            self.posy = self.grassy * GRASS_SIZE[1] + ZOMBIE_PLUSY
        else:
            self.grassy = grassy
            self.posx = posx
            self.posy = self.grassy * GRASS_SIZE[1] + ZOMBIE_PLUSY

        self.images_normal = images[0]
        self.images_eating = images[1]
        self.images_boom = images[2]
        self.eating_flag = eating_flag
        self.boom = False

    def draw(self):
        # print(self.code, self.health, self.posx, self.posy, self.grassy)
        eat_flag = check_eat(self.posx, self.posy)
        if self.boom:
            pos = (self.posx, self.posy)
            window.blit(self.images_boom[self.cur % self.index[2]], pos)
            self.FPSVar = (self.FPSVar + 1) % 6
            if self.FPSVar == 0:
                self.cur += 1
                if self.cur == self.index[2]:
                    self.boom_die()
            return
        if eat_flag is None:
            if self.eating_flag:
                self.cur = 0
                self.eating_flag = False
            pos = (self.posx, self.posy)
            window.blit(self.images_normal[self.cur % self.index[0]], pos)
            self.FPSVar = (self.FPSVar + 1) % 8
            if self.FPSVar == 0:
                self.cur += 1
                self.move()
        else:
            if not self.eating_flag:
                self.cur = 0
                self.eating_flag = True
            pos = (self.posx, self.posy)
            window.blit(self.images_eating[self.cur % self.index[1]], pos)
            self.FPSVar = (self.FPSVar + 1) % 8
            if self.FPSVar == 0:
                self.cur += 1
                if self.cur % self.index[1] == 5:
                    eat_flag.hurt(70)
                    load_sound(random.randint(0, 1) * 2 + 1)

                elif self.cur % self.index[1] == 10:
                    eat_flag.hurt(70)
                    load_sound(2)

    def move(self):
        if self.posx < -150:
            global fail
            fail = True
        self.posx -= self.speed

    def hurt(self, num, status=None):
        self.health -= num
        if self.health <= 0:

            if num == 10000:
                self.instant_die()
            elif status == 'boom':
                self.boom = True
                self.cur = 0
                self.speed = 0
            elif status == 'mine':
                zombie_list.remove(self)
                if win and len(zombie_list) == 0:
                    global trophy
                    trophy = Trophy(self.posx, self.posy)
            else:
                if self.boom:
                    return
                self.die()

    def die(self):
        load_sound(10)
        head_list.append(Head(self.posx, self.posy))
        if self.eating_flag:
            body_list.append(Body(self.posx, self.posy, 1))
        else:
            body_list.append(Body(self.posx, self.posy, 0, self.speed / 8))
        zombie_list.remove(self)
        if win and len(zombie_list) == 0:
            global trophy
            trophy = Trophy(self.posx, self.posy)

    def instant_die(self):
        load_sound(10)
        head_list.append(Head(self.posx, self.posy))
        body_list.append(Body(self.posx, self.posy, 2))
        zombie_list.remove(self)
        if win and len(zombie_list) == 0:
            global trophy
            trophy = Trophy(self.posx, self.posy)

    def boom_die(self):
        zombie_list.remove(self)
        if win and len(zombie_list) == 0:
            global trophy
            trophy = Trophy(self.posx, self.posy)


class NormalZombie(Zombie):
    def __init__(self, posx=None, grassy=None, cur=0, FPSVar=0, eating_flag=False):
        Zombie.__init__(self, 0, ZOMBIE_SPEED, 300, IMAGE_Normal_zombie, posx, grassy, cur, FPSVar, eating_flag)


class ConeHeadZombie(Zombie):
    def __init__(self, posx=None, grassy=None, cur=0, FPSVar=0, eating_flag=False):
        Zombie.__init__(self, 1, ZOMBIE_SPEED, 370, IMAGE_Conehead_zombie, posx, grassy, cur, FPSVar, eating_flag)

    def hurt(self, num, status=None):
        self.health -= num

        if self.health <= 0:
            if num == 10000:
                self.instant_die()
            elif status == 'boom':
                self.boom = True
                self.cur = 0
                self.speed = 0
            elif status == 'mine':
                zombie_list.remove(self)
                if win and len(zombie_list) == 0:
                    global trophy
                    trophy = Trophy(self.posx, self.posy)
            else:
                if self.boom:
                    return
                zombie_list.remove(self)
                zombie_list.append(NormalZombie(self.posx + 10, self.grassy, self.cur, self.FPSVar, self.eating_flag))


class BucketHeadZombie(Zombie):
    def __init__(self, posx=None, grassy=None, cur=0, FPSVar=0, eating_flag=False):
        Zombie.__init__(self, 2, ZOMBIE_SPEED, 1100, IMAGE_Buckethead_zombie, posx, grassy, cur, FPSVar, eating_flag)

    def hurt(self, num, status=None):
        self.health -= num
        # print(self.posx, self.grassy, self.health)
        if self.health <= 0:
            if num == 10000:
                self.instant_die()
            elif status == 'boom':
                # print(self.posx, self.grassy, self.health, 'boom')
                self.boom = True
                self.cur = 0
                self.speed = 0
            elif status == 'mine':
                zombie_list.remove(self)
                if win and len(zombie_list) == 0:
                    global trophy
                    trophy = Trophy(self.posx, self.posy)
            else:
                if self.boom:
                    return
                zombie_list.remove(self)
                zombie_list.append(NormalZombie(self.posx, self.grassy, self.cur, self.FPSVar, self.eating_flag))


class Plant(object):
    def __init__(self, move, code, index, FPSDiv, images, pos=None):
        self.code = code
        self.index = index
        self.cur = 0
        self.FPSVar = 0
        self.FPSDiv = FPSDiv
        self.move = move
        self.images = images
        self.alpha_image = self.images[0].copy()
        self.alpha_image.set_alpha(100)
        self.health = 300
        if move:
            self.grassx = (pos[0] - GRASS_POS[0]) // GRASS_SIZE[0]
            self.grassy = (pos[1] - GRASS_POS[1]) // GRASS_SIZE[1]

    def draw(self, pos):
        window.blit(self.images[0], pos)

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        window.blit(self.images[self.cur % self.index], grow_pos)
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1

    def draw_alpha(self, pos):
        x = (pos[0] - GRASS_POS[0]) // GRASS_SIZE[0]
        y = (pos[1] - GRASS_POS[1]) // GRASS_SIZE[1]
        grow_pos = (GRASS_POS[0] + x * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + y * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        window.blit(self.alpha_image, grow_pos)

    def hurt(self, num):
        self.health -= num
        if self.health <= 0:
            self.die()

    def die(self):
        load_sound(30)
        plant_list.remove(self)
        remove_plant_in_GRASS(self)


class Sunflower(Plant):
    def __init__(self, move, pos=None):
        Plant.__init__(self, move, 1, 18, 5, IMAGE_Sunflower, pos)

    def produce(self):
        sun_falling.append(Sun(self.grassx, self.grassy))

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        window.blit(self.images[self.cur % self.index], grow_pos)
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1
            if (self.cur + 9) % 288 == 0:
                self.produce()


class Peashooter(Plant):
    def __init__(self, move, pos=None):
        Plant.__init__(self, move, 0, 13, 6, IMAGE_Peashooter, pos)

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        window.blit(self.images[self.cur % self.index], grow_pos)
        return self.next()

    def next(self):
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1
        if self.cur % self.index == 2 and self.FPSVar == 0:
            if discover_zombie(self.grassx, self.grassy):
                return Bullet(self.grassx, self.grassy)
        return None


class CherryBomb(Plant):
    def __init__(self, move, pos=None):
        Plant.__init__(self, move, 2, 11, 6, IMAGE_Cherrybomb, pos)
        self.health = 3000

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        if self.cur >= 7:
            grow_pos_v = (grow_pos[0] - 50, grow_pos[1] - 30)
            window.blit(self.images[self.cur], grow_pos_v)
        else:
            window.blit(self.images[self.cur], grow_pos)
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1
            if self.cur == self.index - 4:
                load_sound(22)
                self.blow()
            if self.cur == self.index - 1:
                plant_list.remove(self)
                remove_plant_in_GRASS(self)

    def blow(self):
        for each in zombie_list:
            # temp_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
            #             GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
            temp_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0],
                        GRASS_POS[1] + self.grassy * GRASS_SIZE[1])
            # if ((each.posx - temp_pos[0]) ** 2 + (each.posy - temp_pos[1]) ** 2) ** 0.5 <= GRASS_SIZE[0] * 2:
            # if -GRASS_SIZE[0] <= each.posx - temp_pos[0] <= GRASS_SIZE[0] and -GRASS_SIZE[0] <= each.posx - temp_pos[0] <= GRASS_SIZE[0]:
            if -GRASS_SIZE[0] * 2.5 <= each.posx - temp_pos[0] <= GRASS_SIZE[0] and -GRASS_SIZE[1] * 2 <= each.posy - \
                    temp_pos[1] <= GRASS_SIZE[1]:
                each.hurt(1800, 'boom')


class WallNut(Plant):
    def __init__(self, move, pos=None):
        Plant.__init__(self, move, 3, 3, 6, IMAGE_Wallnut, pos)
        self.health = 4000

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        if self.health >= 8000 / 3:
            window.blit(self.images[0], grow_pos)
        elif self.health >= 4000 / 3:
            window.blit(self.images[1], grow_pos)
        else:
            window.blit(self.images[2], grow_pos)


class PotatoMine(Plant):
    def __init__(self, move, pos=None):
        Plant.__init__(self, move, 4, 8, 10, IMAGE_Potatomine, pos)
        self.initimage = IMAGE_Potatomine_init
        self.blowimage = IMAGE_Potatomine_blow
        self.ready = False
        self.blowing = False

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        if self.ready:
            zombie_to_blow = self.check_collision()
            if zombie_to_blow is not None:
                grow_pos_v = (grow_pos[0] - 30, grow_pos[1] - 25)
                window.blit(self.blowimage, grow_pos_v)
                self.ready = False
                self.blowing = True
                load_sound(23)
                zombie_to_blow.hurt(1800, 'mine')
                self.cur = 0
            else:
                window.blit(self.images[self.cur % self.index], grow_pos)
        else:
            if self.blowing:
                grow_pos_v = (grow_pos[0] - 40, grow_pos[1] - 60)
                window.blit(self.blowimage, grow_pos_v)
                if self.cur == 8:
                    plant_list.remove(self)
                    remove_plant_in_GRASS(self)
            else:
                grow_pos_v = (grow_pos[0] + 20, grow_pos[1] + 10)
                window.blit(self.initimage, grow_pos_v)
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1
            if not self.blowing and not self.ready and self.cur == 15 * FPS / self.FPSDiv:
                self.ready = True

    def check_collision(self):
        for each in zombie_list:
            temp_posx = GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code]
            if -90 <= each.posx - temp_posx <= -10 and each.grassy == self.grassy:
                return each
        return None


class DoublePea(Peashooter):
    def __init__(self, move, pos=None):
        Peashooter.__init__(self, move, pos)
        self.code = 5
        self.index = 15
        self.images = IMAGE_Doublepea
        self.alpha_image = self.images[0].copy()
        self.alpha_image.set_alpha(100)

    def next(self):
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1
        if (self.cur % self.index == 2 or self.cur % self.index == 8) and self.FPSVar == 0:
            if discover_zombie(self.grassx, self.grassy):
                return Bullet(self.grassx, self.grassy)
        return None


class Chomper(Plant):
    def __init__(self, move, pos=None):
        Plant.__init__(self, move, 6, (13, 9, 6), 8, IMAGE_Chomper, pos)
        self.attackimages = IMAGE_Chomper_attack
        self.digestimages = IMAGE_Chomper_digest
        self.chewingtime = 42 * FPS / self.FPSDiv
        self.attack = False
        self.ready = True

    def draw_on_grass(self):
        grow_pos = (GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code],
                    GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + GROW_PLUSY[self.code])
        if not self.attack:
            if self.ready:
                window.blit(self.images[self.cur % self.index[0]], grow_pos)
                if self.check_collision():
                    self.attack = True
                    self.cur = 0
            else:
                window.blit(self.digestimages[self.cur % self.index[2]], grow_pos)
                if self.cur == self.chewingtime:
                    self.cur = 0
                    self.ready = True
        else:
            window.blit(self.attackimages[self.cur], grow_pos)
            if self.cur == 6 and self.FPSVar == 0:
                load_sound(24)
                self.eat()
            if self.cur == self.index[1] - 1:
                self.cur = 0
                self.attack = False
                self.ready = False
        self.FPSVar = (self.FPSVar + 1) % self.FPSDiv
        if self.FPSVar == 0:
            self.cur += 1

    def check_collision(self):
        for each in zombie_list:
            temp_posx = GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code]
            if -40 <= each.posx - temp_posx <= 80 and self.grassy == each.grassy:
                return True

    def eat(self):
        for each in zombie_list:
            temp_posx = GRASS_POS[0] + self.grassx * GRASS_SIZE[0] + GROW_PLUSX[self.code]
            if -40 <= each.posx - temp_posx <= 80 and self.grassy == each.grassy:
                each.hurt(1800, 'mine')
                return


class Bullet(object):
    def __init__(self, x, start_y):
        self.grassy = start_y
        self.posx = GRASS_POS[0] + x * GRASS_SIZE[0] + BULLET_START_POS[0]
        self.posy = GRASS_POS[1] + start_y * GRASS_SIZE[1] + BULLET_START_POS[1]
        self.speed = GRASS_SIZE[0] * 0.05
        self.image_normal = IMAGE_Pea_bullet
        self.image_explode = IMAGE_Pea_bullet_explode

    def draw(self):
        window.blit(self.image_normal, (self.posx, self.posy))

    def move(self):
        collision_flag = check_collision(self.posx, self.grassy, 20)
        if self.posx <= GRASS_POS[0] + GRASS_NUM[0] * GRASS_SIZE[0] + 5:
            if collision_flag is None:
                self.posx += self.speed
            else:
                if collision_flag.code == 0:
                    load_sound(random.randint(7, 9))
                elif collision_flag.code == 1:
                    load_sound(random.randint(26, 27))
                elif collision_flag.code == 2:
                    load_sound(random.randint(28, 29))
                window.blit(self.image_explode, (self.posx, self.posy))
                bullets.remove(self)
        else:
            bullets.remove(self)


class MinigamesBullet(Bullet):
    def __init__(self, x, start_y):
        Bullet.__init__(self, x, start_y)

    def move(self):
        collision_flag = minigames_check_collision(self.posx, self.grassy, 20)
        if self.posx <= GRASS_POS[0] + GRASS_NUM[0] * GRASS_SIZE[0] + 5:
            if collision_flag is None:
                self.posx += self.speed
            else:
                if collision_flag.code == 0:
                    load_sound(random.randint(7, 9))
                elif collision_flag.code == 1:
                    load_sound(random.randint(26, 27))
                elif collision_flag.code == 2:
                    load_sound(random.randint(28, 29))
                window.blit(self.image_explode, (self.posx, self.posy))
                bullets.remove(self)
        else:
            bullets.remove(self)


class Sun(object):
    sun_size = (78, 78)
    sun_point = 25

    def __init__(self, grassx=None, grassy=None):
        self.index = 22
        self.cur = 0
        self.stop_cur = 0
        self.FPSVar = 0
        self.images = IMAGE_Sun
        self.produced = False
        if grassx is None and grassy is None:
            self.posx = random.randint(GRASS_POS[0] - 40, GRASS_POS[0] + GRASS_NUM[0] * GRASS_SIZE[0] - 40)
            self.posy = -80
            self.endy = random.randint(GRASS_POS[1], GRASS_POS[1] + GRASS_NUM[1] * GRASS_SIZE[1] - Sun.sun_size[1] + 20)
        else:
            self.posx = random.randint(GRASS_POS[0] + grassx * GRASS_SIZE[0] - 40,
                                       GRASS_POS[0] + (grassx + 1) * GRASS_SIZE[0] - 40)
            self.posy = GRASS_POS[1] + grassy * GRASS_SIZE[1] + 10
            self.endy = random.randint(GRASS_POS[1] + grassy * GRASS_SIZE[1] + 30,
                                       GRASS_POS[1] + (grassy + 1) * GRASS_SIZE[1] - 50)
            self.produced = True
        self.speed = 1

    def fall(self):
        if self.posy >= self.endy:
            self.speed = 0
            return
        if self.produced:
            return
        self.posy += self.speed

    def draw(self):
        if self.produced:
            highlight = self.images[0]
            highlight.set_alpha(int(255 * self.cur / 15))
            window.blit(highlight, (self.posx, self.posy))
        else:
            window.blit(self.images[self.cur % self.index], (self.posx, self.posy))
        self.FPSVar = (self.FPSVar + 1) % 3
        if self.FPSVar == 0:
            self.cur += 1
            if self.cur == 15 and self.produced:
                self.produced = False
            if self.speed != 0:
                self.stop_cur = self.cur
        if self.cur - self.stop_cur == 160:
            sun_falling.remove(self)


class SunCollecting(object):

    endx = 20
    endy = 10

    collect_time = FPS

    def __init__(self, initpos):
        self.posx = initpos[0]
        self.posy = initpos[1]
        self.image = IMAGE_Sun[10]
        delta_x = initpos[0] + Sun.sun_size[0] // 2 - SunCollecting.endx
        delta_y = initpos[1] + Sun.sun_size[1] // 2 - SunCollecting.endy
        temp_total = (delta_x ** 2 + delta_y ** 2) ** 0.5
        temp_speed = temp_total / SunCollecting.collect_time * 2
        temp_acc = temp_speed ** 2 / (2 * temp_total)
        self.speed_x = temp_speed * delta_x / temp_total
        self.speed_y = temp_speed * delta_y / temp_total
        self.acc_x = temp_acc * delta_x / temp_total
        self.acc_y = temp_acc * delta_y / temp_total

    def move(self):
        self.posx -= int(self.speed_x)
        self.speed_x -= self.acc_x
        self.posy -= int(self.speed_y)
        self.speed_y -= self.acc_y
        if self.speed_x <= 0.5 and self.speed_y <= 0.5:
            sun_collecting.remove(self)

    def draw(self):
        window.blit(self.image, (self.posx, self.posy))


class Brain(object):
    def __init__(self, grassy):
        self.code = -1
        self.grassy = grassy
        self.image = IMAGE_Brain
        self.health = 300

    def draw_on_grass(self):
        window.blit(self.image, (5, GRASS_POS[1] + self.grassy * GRASS_SIZE[1] + 42))

    def hurt(self, num):
        self.health -= num
        if self.health <= 0:
            load_sound(30)
            brain_list.remove(self.grassy)
            plant_list.remove(self)
            if len(brain_list) == 0:
                global trophy
                trophy = Trophy(GRASS_POS[0], GRASS_POS[1] + GRASS_SIZE[1] * self.grassy)


class Lawnmower(object):
    cross_time = 4 * FPS
    readyx = -26
    endx = WINDOW_WIDTH

    def __init__(self, posx, grassy):
        self.posx = posx
        self.posy = GRASS_POS[1] + grassy * GRASS_SIZE[1] + 35
        self.grassy = grassy
        self.ready = False
        self.set_off = False
        self.speed = (Lawnmower.endx + 26) / Lawnmower.cross_time

    def draw(self):
        window.blit(IMAGE_Lawnmower, (self.posx, self.posy))

    def move(self):
        if not self.ready:
            self.posx += self.speed
            if self.posx >= Lawnmower.readyx:
                self.ready = True
        elif not self.set_off:
            collision_flag = check_collision(self.posx, self.grassy, 0)
            if collision_flag is not None:
                load_sound(19)
                self.set_off = True
        else:
            self.posx += self.speed
            check_collision(self.posx, self.grassy, 10000)
        if self.posx >= Lawnmower.endx:
            lawnmower_list.remove(self)


# 占用的草地
class PlantedGrass(object):
    def __init__(self, pos, code):
        self.code = code
        self.grassx = (pos[0] - GRASS_POS[0]) // GRASS_SIZE[0]
        self.grassy = (pos[1] - GRASS_POS[1]) // GRASS_SIZE[1]


# 选卡动画
class MovingCard(object):
    def __init__(self, image, startpos, endpos, code):
        self.image = image
        self.endpos = endpos
        self.posx = startpos[0]
        self.posy = startpos[1]
        self.movingtime = 0.1 * FPS
        self.code = code

        delta_x = endpos[0] - startpos[0]
        delta_y = endpos[1] - startpos[1]
        total = (delta_x ** 2 + delta_y ** 2) ** 0.5
        speed = total / self.movingtime

        self.speed_x = speed * delta_x / total
        self.speed_y = speed * delta_y / total

    def draw(self):
        window.blit(self.image, (self.posx, self.posy))

    def move(self):
        if -3 <= self.posx - self.endpos[0] <= 3 and -3 <= self.posy - self.endpos[1] <= 3:
            moving_card_list.remove(self)
        self.posx += self.speed_x
        self.posy += self.speed_y


class FlagMeter(object):
    def __init__(self):
        self.cur = 0
        self.FPSVar = 0
        self.images = IMAGE_Flagmeter
        self.index = 37

    def draw(self):
        window.blit(self.images[self.cur], (601, 561))
        if self.cur == 36:
            return
        self.FPSVar = (self.FPSVar + 1) % 300
        if self.FPSVar == 0:
            self.cur += 1


def show_text(textstr1, size, color, pos1, textstr2=None, pos2=None):
    # file = pygame.font.SysFont(font_name, size)
    file = pygame.font.Font(FONT, size)
    text1 = file.render(textstr1, True, color)
    window.blit(text1, pos1)
    if textstr2 is not None:
        text2 = file.render(textstr2, True, color)
        window.blit(text2, pos2)


# 加载素材
def load_peashooter():
    temp_pea = []
    for i in range(13):
        file = pygame.image.load('images/Peashooter/Peashooter_{0}.png'.format(i)).convert_alpha()
        temp_pea.append(file)
    return temp_pea


def load_sunflower():
    temp_flower = []
    for i in range(18):
        file = pygame.image.load('images/Sunflower/Sunflower_{0}.png'.format(i)).convert_alpha()
        temp_flower.append(file)
    return temp_flower


def load_cherrybomb():
    temp_bomb = []
    for i in range(1, 12):
        file = pygame.image.load('images/CherryBomb/CherryBomb-{0}.png'.format(i)).convert_alpha()
        temp_bomb.append(file)
    return temp_bomb


def load_wallnut():
    temp_wallnut = []
    for i in range(3):
        file = pygame.image.load('images/WallNut/Wallnut_{0}.png'.format(i)).convert_alpha()
        file = pygame.transform.rotozoom(file, 0, 0.8)
        temp_wallnut.append(file)
    return temp_wallnut


def load_potatomine():
    temp_mine = []
    for i in range(1, 9):
        file = pygame.image.load('images/PotatoMine/PotatoMine/PotatoMine-{0}.png'.format(i)).convert_alpha()
        temp_mine.append(file)
    return temp_mine


def load_chomper():
    chomper = []
    for i in range(13):
        file = pygame.image.load('images/Chomper/Chomper/Chomper_{0}.png'.format(i)).convert_alpha()
        chomper.append(file)
    return chomper


def load_chomper_attack():
    chomper = []
    for i in range(9):
        file = pygame.image.load('images/Chomper/ChomperAttack/ChomperAttack_{0}.png'.format(i)).convert_alpha()
        chomper.append(file)
    return chomper


def load_chomper_digest():
    chomper = []
    for i in range(6):
        file = pygame.image.load('images/Chomper/ChomperDigest/ChomperDigest_{0}.png'.format(i)).convert_alpha()
        chomper.append(file)
    return chomper


def load_doublepea():
    doublepea = []
    for i in range(15):
        file = pygame.image.load('images/RepeaterPea/RepeaterPea_{0}.png'.format(i)).convert_alpha()
        doublepea.append(file)
    return doublepea


def load_sun():
    temp_sun = []
    for i in range(22):
        file = pygame.image.load('images/Sun/Sun_{0}.png'.format(i)).convert_alpha()
        temp_sun.append(file)
    return temp_sun


def load_normal_zombie():
    temp_zombie = []
    for i in range(22):
        file = pygame.image.load('images/NormalZombie/Zombie/Zombie_{0}.png'.format(i)).convert_alpha()
        temp_zombie.append(file)
    return temp_zombie


def load_eating_zombie():
    temp_zombie = []
    for i in range(11):
        file = pygame.image.load('images/NormalZombie/ZombieAttack/ZombieAttack_{0}.png'.format(i)).convert_alpha()
        temp_zombie.append(file)
    return temp_zombie


def load_conehead_zombie():
    temp_zombie = []
    for i in range(22):
        file = pygame.image.load(
            'images/ConeheadZombie/ConeheadZombie/ConeheadZombie_{0}.png'.format(i)).convert_alpha()
        temp_zombie.append(file)
    return temp_zombie


def load_eating_conehead():
    temp_zombie = []
    for i in range(11):
        file = pygame.image.load(
            'images/ConeheadZombie/ConeheadZombieAttack/ConeheadZombieAttack_{0}.png'.format(i)).convert_alpha()
        temp_zombie.append(file)
    return temp_zombie


def load_buckethead_zombie():
    temp_zombie = []
    for i in range(22):
        file = pygame.image.load(
            'images/BucketheadZombie/BucketheadZombie/BucketheadZombie_{0}.png'.format(i)).convert_alpha()
        temp_zombie.append(file)
    return temp_zombie


def load_eating_buckethead():
    temp_zombie = []
    for i in range(11):
        file = pygame.image.load(
            'images/BucketheadZombie/BucketheadZombieAttack/BucketheadZombieAttack_{0}.png'.format(i)).convert_alpha()
        temp_zombie.append(file)
    return temp_zombie


def load_head():
    temp_head = []
    for i in range(17):
        file = pygame.image.load('images/NormalZombie/ZombieHead/ZombieHead_{0}.png'.format(i)).convert_alpha()
        temp_head.append(file)
    return temp_head


def load_body():
    temp_body = []
    for i in range(17):
        file = pygame.image.load('images/NormalZombie/ZombieDie/ZombieDie_{0}.png'.format(i)).convert_alpha()
        temp_body.append(file)
    return temp_body


def load_body_walking():
    temp_body = []
    for i in range(18):
        file = pygame.image.load('images/NormalZombie/ZombieLostHead/ZombieLostHead_{0}.png'.format(i)).convert_alpha()
        temp_body.append(file)
    return temp_body


def load_body_eating():
    temp_body = []
    for i in range(11):
        file = pygame.image.load(
            'images/NormalZombie/ZombieLostHeadAttack/ZombieLostHeadAttack_{0}.png'.format(i)).convert_alpha()
        temp_body.append(file)
    return temp_body


def load_booming_zombie():
    temp_zombie = []
    for i in range(20):
        file = pygame.image.load('images/NormalZombie/BoomDie/BoomDie_{0}.png'.format(i)).convert_alpha()
        temp_zombie.append(file)
    return temp_zombie


def load_flagmeter():
    temp = []
    for i in range(37):
        file = pygame.image.load('images/FlagMeter/FlagMeter_{0}.png'.format(i)).convert_alpha()
        temp.append(file)
    return temp


def load_trophy():
    trophy_file = []
    for i in range(9):
        file = pygame.image.load('images/Trophy/trophy_{0}.png'.format(i)).convert_alpha()
        trophy_file.append(file)
    return trophy_file


def load_images():
    global IMAGE_Start_menu  # (384, 72)

    global IMAGE_Adventure_button
    global IMAGE_Adventure_button_alpha

    global IMAGE_Minigames_button
    global IMAGE_Minigames_button_alpha

    global IMAGE_Minigames_back
    global IMAGE_Minigames_window
    global IMAGE_Minigames_window_alpha

    global IMAGE_Minigames_imzombie

    global IMAGE_Minigames_back_button
    global IMAGE_Minigames_back_button_alpha

    global IMAGE_Background_night

    global IMAGE_Help_button
    global IMAGE_Help_button_alpha
    global IMAGE_Quit_button
    global IMAGE_Quit_button_alpha
    global IMAGE_Options_button
    global IMAGE_Options_button_alpha

    global IMAGE_Help
    global IMAGE_Back_button_alpha

    global IMAGE_Background
    global IMAGE_Background_full

    global IMAGE_Choose_seeds
    global IMAGE_Ready_button

    global IMAGE_Start_planting

    global IMAGE_Sun_count

    global IMAGE_Pause_button_left
    global IMAGE_Pause_button_middle
    global IMAGE_Pause_button_right
    global IMAGE_Pause_button_alpha

    global IMAGE_Volume_knob
    global IMAGE_Volume

    global IMAGE_Pause_screen

    global IMAGE_Long_button_alpha

    global IMAGE_Back_to_game
    global IMAGE_Back_to_game_alpha

    global IMAGE_Peashooter
    global IMAGE_Card_peashooter
    global IMAGE_Card_peashooter_alpha

    global IMAGE_Sun

    global IMAGE_Lawnmower

    global IMAGE_Sunflower
    global IMAGE_Card_sunflower
    global IMAGE_Card_sunflower_alpha

    global IMAGE_Pea_bullet
    global IMAGE_Pea_bullet_explode

    global IMAGE_Cherrybomb
    global IMAGE_Card_cherrybomb
    global IMAGE_Card_cherrybomb_alpha

    global IMAGE_Wallnut
    global IMAGE_Card_wallnut
    global IMAGE_Card_wallnut_alpha

    global IMAGE_Potatomine
    global IMAGE_Potatomine_init
    global IMAGE_Potatomine_blow
    global IMAGE_Card_potatomine
    global IMAGE_Card_potatomine_alpha

    global IMAGE_Chomper
    global IMAGE_Chomper_attack
    global IMAGE_Chomper_digest
    global IMAGE_Card_chomper
    global IMAGE_Card_chomper_alpha

    global IMAGE_Doublepea
    global IMAGE_Card_doublepea
    global IMAGE_Card_doublepea_alpha

    global IMAGE_Normal_zombie

    global IMAGE_Conehead_zombie

    global IMAGE_Buckethead_zombie

    global IMAGE_Zombie_head
    global IMAGE_Zombie_body
    global IMAGE_Zombie_body_walking
    global IMAGE_Zombie_body_eating

    global IMAGE_Shovel
    global IMAGE_Shovel_bank

    global IMAGE_Flagmeter

    global IMAGE_Huge_wave
    global IMAGE_Final_wave

    global IMAGE_Lost

    global IMAGE_Dialog

    global card_image_list

    global IMAGE_Trophy
    global IMAGE_Trophy_get

    global IMAGE_Card_normal_zombie
    global IMAGE_Card_normal_zombie_alpha

    global IMAGE_Card_cone_zombie
    global IMAGE_Card_cone_zombie_alpha

    global IMAGE_Card_bucket_zombie
    global IMAGE_Card_bucket_zombie_alpha

    global IMAGE_Zombie_line

    global IMAGE_Brain

    IMAGE_Start_menu = pygame.image.load('images/MainMenu.png').convert()

    IMAGE_Adventure_button = pygame.image.load('images/Buttons/SelectorScreen_Adventure_button.png').convert_alpha()
    IMAGE_Adventure_button_alpha = pygame.image.load(
        'images/Buttons/SelectorScreen_Adventure_highlight.png').convert_alpha()

    IMAGE_Minigames_button = pygame.image.load('images/Buttons/SelectorScreen_Survival_button.png').convert_alpha()
    IMAGE_Minigames_button_alpha = pygame.image.load('images/Buttons/SelectorScreen_Survival_highlight.png').convert_alpha()

    IMAGE_Minigames_back = pygame.image.load('images/Buttons/Challenge_Background.jpg').convert_alpha()
    IMAGE_Minigames_window = pygame.image.load('images/Buttons/Challenge_Window.png').convert_alpha()
    IMAGE_Minigames_window_alpha = pygame.image.load('images/Buttons/Challenge_Window_Highlight.png').convert_alpha()

    IMAGE_Minigames_imzombie = pygame.image.load('images/Buttons/minigames_imzombie.png').convert_alpha()

    IMAGE_Minigames_back_button = pygame.image.load('images/Buttons/Almanac_IndexButton.png').convert_alpha()
    IMAGE_Minigames_back_button_alpha = pygame.image.load('images/Buttons/Almanac_IndexButtonHighlight.png').convert_alpha()

    IMAGE_Background_night = pygame.image.load('images/Background_3.png').convert()

    IMAGE_Help_button = pygame.image.load('images/Buttons/SelectorScreen_Help1.png').convert_alpha()
    IMAGE_Help_button_alpha = pygame.image.load('images/Buttons/SelectorScreen_Help2.png').convert_alpha()
    IMAGE_Quit_button = pygame.image.load('images/Buttons/SelectorScreen_Quit1.png').convert_alpha()
    IMAGE_Quit_button_alpha = pygame.image.load('images/Buttons/SelectorScreen_Quit2.png').convert_alpha()
    IMAGE_Options_button = pygame.image.load('images/Buttons/SelectorScreen_Options1.png').convert_alpha()
    IMAGE_Options_button_alpha = pygame.image.load('images/Buttons/SelectorScreen_Options2.png').convert_alpha()

    IMAGE_Help = pygame.image.load('images/Help.png').convert()
    IMAGE_Back_button_alpha = pygame.image.load('images/Buttons/BackButton.png').convert_alpha()

    IMAGE_Background = pygame.image.load('images/Background_0.png').convert()
    IMAGE_Background_full = pygame.image.load('images/Backgroundfull.png').convert()

    IMAGE_Choose_seeds = pygame.image.load('images/SeedChooser_Background.png').convert_alpha()
    IMAGE_Ready_button = [pygame.image.load('images/Buttons/SeedChooser_Button_Disabled.png').convert_alpha(),
                          pygame.image.load('images/Buttons/SeedChooser_Button.png').convert_alpha(),
                          pygame.image.load('images/Buttons/SeedChooser_Button_Glow.png').convert_alpha()]
    for i in range(3):
        IMAGE_Ready_button[i] = pygame.transform.rotozoom(IMAGE_Ready_button[i], 0, 2)

    IMAGE_Start_planting = [pygame.image.load('images/StartReady.png').convert_alpha(),
                            pygame.image.load('images/StartSet.png').convert_alpha(),
                            pygame.image.load('images/StartPlant.png').convert_alpha()]

    IMAGE_Sun_count = pygame.image.load('images/sun_count.png').convert_alpha()  # 76x87

    IMAGE_Pause_button_left = pygame.image.load('images/Buttons/button_left.png').convert_alpha()
    IMAGE_Pause_button_middle = pygame.image.load('images/Buttons/button_middle.png').convert_alpha()
    IMAGE_Pause_button_right = pygame.image.load('images/Buttons/button_right.png').convert_alpha()
    IMAGE_Pause_button_alpha = pygame.image.load('images/Buttons/button.png').convert_alpha()

    IMAGE_Volume = pygame.image.load('images/Buttons/options_sliderslot.png').convert_alpha()
    IMAGE_Volume_knob = pygame.image.load('images/Buttons/options_sliderknob2.png').convert_alpha()

    IMAGE_Pause_screen = pygame.image.load('images/Buttons/PauseScreen.png').convert_alpha()

    IMAGE_Long_button_alpha = pygame.image.load('images/Buttons/long_button2.png').convert_alpha()

    IMAGE_Back_to_game = pygame.image.load('images/Buttons/options_backtogamebutton0.png').convert_alpha()
    IMAGE_Back_to_game_alpha = pygame.image.load('images/Buttons/options_backtogamebutton2.png').convert_alpha()

    IMAGE_Peashooter = load_peashooter()
    IMAGE_Card_peashooter = pygame.image.load('images/Cards/card_peashooter.png').convert_alpha()  # 65x90
    IMAGE_Card_peashooter_alpha = pygame.image.load('images/Cards/card_peashooter_alpha.png').convert_alpha()

    IMAGE_Sun = load_sun()

    IMAGE_Lawnmower = pygame.image.load('images/lawnmower.png').convert_alpha()

    IMAGE_Sunflower = load_sunflower()
    IMAGE_Card_sunflower = pygame.image.load('images/Cards/card_sunflower.png').convert_alpha()
    IMAGE_Card_sunflower_alpha = pygame.image.load('images/Cards/card_sunflower_alpha.png').convert_alpha()

    IMAGE_Pea_bullet = pygame.image.load('images/Peashooter/PeaNormal.png').convert_alpha()
    IMAGE_Pea_bullet_explode = pygame.image.load('images/Peashooter/PeaNormalExplode_0.png').convert_alpha()

    IMAGE_Cherrybomb = load_cherrybomb()
    IMAGE_Card_cherrybomb = pygame.image.load('images/Cards/card_cherrybomb.png').convert_alpha()
    IMAGE_Card_cherrybomb_alpha = pygame.image.load('images/Cards/card_cherrybomb_alpha.png').convert_alpha()

    IMAGE_Wallnut = load_wallnut()
    IMAGE_Card_wallnut = pygame.image.load('images/Cards/card_wallnut.png').convert_alpha()
    IMAGE_Card_wallnut_alpha = pygame.image.load('images/Cards/card_wallnut_alpha.png').convert_alpha()

    IMAGE_Potatomine = load_potatomine()
    IMAGE_Potatomine_init = pygame.image.load('images/PotatoMine/PotatoMineInit.png').convert_alpha()
    IMAGE_Potatomine_blow = pygame.image.load('images/PotatoMine/PotatoMine_mashed.png').convert_alpha()
    IMAGE_Potatomine_blow = pygame.transform.rotozoom(IMAGE_Potatomine_blow, 0, 1.2)
    IMAGE_Card_potatomine = pygame.image.load('images/Cards/card_potatomine.png').convert_alpha()
    IMAGE_Card_potatomine_alpha = pygame.image.load('images/Cards/card_potatomine_alpha.png').convert_alpha()

    IMAGE_Chomper = load_chomper()
    IMAGE_Chomper_attack = load_chomper_attack()
    IMAGE_Chomper_digest = load_chomper_digest()
    IMAGE_Card_chomper = pygame.image.load('images/Cards/card_chomper.png').convert_alpha()
    IMAGE_Card_chomper_alpha = pygame.image.load('images/Cards/card_chomper_alpha.png').convert_alpha()

    IMAGE_Doublepea = load_doublepea()
    IMAGE_Card_doublepea = pygame.image.load('images/Cards/card_doublepea.png').convert_alpha()
    IMAGE_Card_doublepea_alpha = pygame.image.load('images/Cards/card_doublepea_alpha.png').convert_alpha()

    IMAGE_Normal_zombie = [load_normal_zombie(), load_eating_zombie(), load_booming_zombie()]

    IMAGE_Conehead_zombie = [load_conehead_zombie(), load_eating_conehead(), load_booming_zombie()]

    IMAGE_Buckethead_zombie = [load_buckethead_zombie(), load_eating_buckethead(), load_booming_zombie()]

    IMAGE_Zombie_head = load_head()
    IMAGE_Zombie_body = load_body()
    IMAGE_Zombie_body_walking = load_body_walking()
    IMAGE_Zombie_body_eating = load_body_eating()

    IMAGE_Shovel = pygame.image.load('images/Shovel.png').convert_alpha()
    IMAGE_Shovel_bank = pygame.image.load('images/ShovelBank.png').convert_alpha()

    IMAGE_Flagmeter = load_flagmeter()

    IMAGE_Huge_wave = pygame.image.load('images/APPROACHING.png').convert_alpha()
    IMAGE_Final_wave = pygame.image.load('images/FinalWave.png').convert_alpha()

    IMAGE_Lost = pygame.image.load('images/Lost.png').convert_alpha()

    IMAGE_Dialog = pygame.image.load('images/Buttons/Dialog.png').convert_alpha()

    card_image_list = [IMAGE_Card_peashooter, IMAGE_Card_sunflower, IMAGE_Card_cherrybomb,
                       IMAGE_Card_wallnut, IMAGE_Card_potatomine, IMAGE_Card_doublepea, IMAGE_Card_chomper]

    IMAGE_Trophy = load_trophy()
    IMAGE_Trophy_get = pygame.image.load('images/trophy.png').convert_alpha()

    IMAGE_Card_normal_zombie = pygame.image.load('images/Cards/card_normal_zombie.png').convert()
    IMAGE_Card_normal_zombie_alpha = pygame.image.load('images/Cards/card_normal_zombie_alpha.png').convert()

    IMAGE_Card_cone_zombie = pygame.image.load('images/Cards/card_cone_zombie.png').convert()
    IMAGE_Card_cone_zombie_alpha = pygame.image.load('images/Cards/card_cone_zombie_alpha.png').convert()

    IMAGE_Card_bucket_zombie = pygame.image.load('images/Cards/card_bucket_zombie.png').convert()
    IMAGE_Card_bucket_zombie_alpha = pygame.image.load('images/Cards/card_bucket_zombie_alpha.png').convert()

    IMAGE_Zombie_line = pygame.image.load('images/Wallnut_bowlingstripe.png').convert_alpha()

    IMAGE_Brain = pygame.image.load('images/brain.png').convert_alpha()


def load_music(num, times, paused=False, loading_flag=False):
    # print(MUSIC_POS)
    if loading_flag:
        pygame.mixer.music.load('music/start0.mp3')
        pygame.mixer.music.load('music/grasswalk0.mp3')
        pygame.mixer.music.load('music/Choose your seeds.mp3')
        pygame.mixer.music.load('music/mountains.mp3')
        return

    if num == 0:
        music = 'music/start0.mp3'
    elif num == 1:
        music = 'music/grasswalk0.mp3'
    elif num == 2:
        music = 'music/Choose your seeds.mp3'
    elif num == 4:
        music = 'music/mountains.mp3'
    if pygame.mixer.music.get_busy():
        pygame.mixer.music.stop()
    if paused:
        if num == 0:
            music = 'music/start0.mp3'
        elif num == 1:
            music = 'music/grasswalk0.mp3'
        elif num == 2:
            music = 'music/Choose your seeds.mp3'
        elif num == 4:
            music = 'music/mountains.mp3'
    pygame.mixer.music.load(music)
    pygame.mixer.music.set_volume(MUSIC_VOLUME)
    if paused:
        pygame.mixer.music.play(times, MUSIC_POS)
    else:
        pygame.mixer.music.play(times)


def load_sound(num, loading_flag=False):

    file = ['sound/plant.ogg', 'sound/chomp0.ogg', 'sound/chomp1.ogg', 'sound/chomp2.ogg', 'sound/seedlift.ogg',
            'sound/shovel.ogg', 'sound/tap.ogg', 'sound/splat0.ogg', 'sound/splat1.ogg', 'sound/splat2.ogg',
            'sound/limbs_pop.ogg', 'sound/zombie_falling_1.ogg', 'sound/zombie_falling_2.ogg', 'sound/tap2.ogg',
            'sound/buzzer.ogg', 'sound/bleep.ogg', 'sound/paper.ogg', 'sound/gravebutton.ogg', 'sound/pause.ogg',
            'sound/lawnmower.ogg', 'sound/scream.ogg', 'sound/losemusic.ogg', 'sound/cherrybomb.ogg',
            'sound/potato_mine.ogg', 'sound/bigchomp.ogg', 'sound/frozen.ogg', 'sound/plastichit.ogg',
            'sound/plastichit2.ogg', 'sound/shieldhit.ogg', 'sound/shieldhit2.ogg', 'sound/gulp.ogg',
            'sound/readysetplant.ogg', 'sound/finalwave.ogg', 'sound/hugewave.ogg', 'sound/awooga.ogg',
            'sound/winmusic.ogg', 'sound/buttonclick.ogg', 'sound/siren.ogg']

    if loading_flag:
        pygame.mixer.Sound(file[num])
        return
    sound = pygame.mixer.Sound(file[num])
    sound.set_volume(SOUND_VOLUME)
    sound.play()


# 设置部件
def set_background(num, pos=None):
    if num == 0:
        window.blit(IMAGE_Start_menu, POS)
        return
    if num == 1:
        window.blit(IMAGE_Background, POS)
        return
    if num == 2:
        window.blit(IMAGE_Background_full, pos)
        return
    if num == 3:
        window.blit(IMAGE_Background_night, POS)
        return


def set_window(title, size):
    global window
    window = pygame.display.set_mode(size)
    pygame.display.set_caption(title)


def set_shovel(pos=(GRASS_POS[0] + 7 * GRASS_SIZE[0], 0)):
    window.blit(IMAGE_Shovel_bank, pos)


def set_sun(sun, pos=SUN_POS):

    text_pos = (47 - len(sun) * 6, 55)
    if pos != SUN_POS:
        text_pos = (text_pos[0] - SUN_POS[0] + pos[0], text_pos[1] + pos[1] - SUN_POS[1])
    sun_box = Button(pos, IMAGE_Sun_count)
    sun_box.draw()

    show_text(sun, 25, (0, 0, 0), text_pos)


def set_minigames_card(zombie, pos, type=None):
    if zombie == 0:
        if type is None:
            window.blit(IMAGE_Card_normal_zombie, pos)
            return
        window.blit(IMAGE_Card_normal_zombie_alpha, pos)
        return
    if zombie == 1:
        if type is None:
            window.blit(IMAGE_Card_cone_zombie, pos)
            return
        window.blit(IMAGE_Card_cone_zombie_alpha, pos)
        return
    if zombie == 2:
        if type is None:
            window.blit(IMAGE_Card_bucket_zombie, pos)
            return
        window.blit(IMAGE_Card_bucket_zombie_alpha, pos)
        return


def set_cards(plant, pos, type=None):
    if plant == 0:
        if type is None:
            window.blit(IMAGE_Card_peashooter, pos)
            return
        window.blit(IMAGE_Card_peashooter_alpha, pos)
        return
    if plant == 1:
        if type is None:
            window.blit(IMAGE_Card_sunflower, pos)
            return
        window.blit(IMAGE_Card_sunflower_alpha, pos)
        return
    if plant == 2:
        if type is None:
            window.blit(IMAGE_Card_cherrybomb, pos)
            return
        window.blit(IMAGE_Card_cherrybomb_alpha, pos)
        return
    if plant == 3:
        if type is None:
            window.blit(IMAGE_Card_wallnut, pos)
            return
        window.blit(IMAGE_Card_wallnut_alpha, pos)
        return
    if plant == 4:
        if type is None:
            window.blit(IMAGE_Card_potatomine, pos)
            return
        window.blit(IMAGE_Card_potatomine_alpha, pos)
        return
    if plant == 5:
        if type is None:
            window.blit(IMAGE_Card_doublepea, pos)
            return
        window.blit(IMAGE_Card_doublepea_alpha, pos)
        return
    if plant == 6:
        if type is None:
            window.blit(IMAGE_Card_chomper, pos)
            return
        window.blit(IMAGE_Card_chomper_alpha, pos)
        return


def set_frozen_time(code, last_time, cur_time, pos):
    size = CARD_SIZE[1] * (last_time + FROZEN_TIME[code] * FPS - cur_time) / (FROZEN_TIME[code] * FPS)
    if size > 0:
        temp_card = pygame.Surface((CARD_SIZE[0], size))
        temp_card.fill((0, 0, 0))
        temp_card.set_alpha(100)
        window.blit(temp_card, pos)


def set_pause_button(status, pos, lost=False):
    if on_pause(pos) and status == 2:
        window.blit(IMAGE_Pause_button_alpha, PAUSE_BUTTON_ALPHA)
    else:
        window.blit(IMAGE_Pause_button_left, PAUSE_BUTTON[0])
        window.blit(IMAGE_Pause_button_middle, PAUSE_BUTTON[1])
        window.blit(IMAGE_Pause_button_right, PAUSE_BUTTON[2])

    if lost:
        text_pos1 = (712, 2)
        text_pos2 = (715, 1)
        text = '主菜单'
    else:
        text_pos1 = (721, 2)
        text_pos2 = (724, 1)
        text = '菜单'

    if on_pause(pos):
        if status == 2:
            show_text(text, 18, FONT_COLOR[1], text_pos2)
        else:
            show_text(text, 18, FONT_COLOR[1], text_pos1)
    else:
        show_text(text, 18, FONT_COLOR[0], text_pos1)


def set_back_to_game(clicked, pos):
    if 242 <= pos[0] <= 556 and 452 <= pos[1] <= 520:
        if clicked == 1:
            window.blit(IMAGE_Back_to_game_alpha, BACK_TO_GAME_ALPHA)
            show_text('返回游戏', 42, FONT_COLOR[1], (316, 462))
        else:
            window.blit(IMAGE_Back_to_game, BACK_TO_GAME)
            show_text('返回游戏', 42, FONT_COLOR[1], (316, 460))
    else:
        window.blit(IMAGE_Back_to_game, BACK_TO_GAME)
        show_text('返回游戏', 42, FONT_COLOR[0], (316, 460))


def set_option_up(clicked, pos):
    if PAUSE_OPTION_UP[0][0] <= pos[0] <= PAUSE_OPTION_UP[4][0] + 35 and PAUSE_OPTION_UP[0][1] <= pos[1] <= \
            PAUSE_OPTION_UP[0][1] + 46:
        if clicked == 3:
            window.blit(IMAGE_Long_button_alpha, PAUSE_OPTION_UP_ALPHA)
            show_text('主菜单', 18, FONT_COLOR[1], (PAUSE_OPTION_UP[0][0] + 76, PAUSE_OPTION_UP[0][1] + 12))
        else:
            window.blit(IMAGE_Pause_button_left, PAUSE_OPTION_UP[0])
            window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_UP[1])
            window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_UP[2])
            window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_UP[3])
            window.blit(IMAGE_Pause_button_right, PAUSE_OPTION_UP[4])
            show_text('主菜单', 18, FONT_COLOR[1], (PAUSE_OPTION_UP[0][0] + 76, PAUSE_OPTION_UP[0][1] + 10))
    else:
        window.blit(IMAGE_Pause_button_left, PAUSE_OPTION_UP[0])
        window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_UP[1])
        window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_UP[2])
        window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_UP[3])
        window.blit(IMAGE_Pause_button_right, PAUSE_OPTION_UP[4])
        show_text('主菜单', 18, FONT_COLOR[0], (PAUSE_OPTION_UP[0][0] + 76, PAUSE_OPTION_UP[0][1] + 10))


def set_option_down(clicked, pos):
    if PAUSE_OPTION_DOWN[0][0] <= pos[0] <= PAUSE_OPTION_DOWN[4][0] + 35 and PAUSE_OPTION_DOWN[0][1] <= pos[1] <= \
            PAUSE_OPTION_DOWN[0][1] + 46:
        if clicked == 2:
            window.blit(IMAGE_Long_button_alpha, PAUSE_OPTION_DOWN_ALPHA)
            show_text('退出游戏', 18, FONT_COLOR[1], (PAUSE_OPTION_DOWN[0][0] + 67, PAUSE_OPTION_DOWN[0][1] + 12))
        else:
            window.blit(IMAGE_Pause_button_left, PAUSE_OPTION_DOWN[0])
            window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_DOWN[1])
            window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_DOWN[2])
            window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_DOWN[3])
            window.blit(IMAGE_Pause_button_right, PAUSE_OPTION_DOWN[4])
            show_text('退出游戏', 18, FONT_COLOR[1], (PAUSE_OPTION_DOWN[0][0] + 67, PAUSE_OPTION_DOWN[0][1] + 10))
    else:
        window.blit(IMAGE_Pause_button_left, PAUSE_OPTION_DOWN[0])
        window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_DOWN[1])
        window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_DOWN[2])
        window.blit(IMAGE_Pause_button_middle, PAUSE_OPTION_DOWN[3])
        window.blit(IMAGE_Pause_button_right, PAUSE_OPTION_DOWN[4])
        show_text('退出游戏', 18, FONT_COLOR[0], (PAUSE_OPTION_DOWN[0][0] + 67, PAUSE_OPTION_DOWN[0][1] + 10))


def set_start_again(clicked, pos):
    text_pos1 = (364, 356)
    text_pos2 = (365, 359)
    if LOST_RESTART[0][0] <= pos[0] <= LOST_RESTART[4][0] + 46 and LOST_RESTART[0][1] <= pos[1] <= LOST_RESTART[0][
        1] + 46:
        if clicked == 1:
            window.blit(IMAGE_Long_button_alpha, LOST_RESTART_ALPHA)
            show_text('重新尝试', 18, FONT_COLOR[1], text_pos2)
        else:
            window.blit(IMAGE_Pause_button_left, LOST_RESTART[0])
            window.blit(IMAGE_Pause_button_middle, LOST_RESTART[1])
            window.blit(IMAGE_Pause_button_middle, LOST_RESTART[2])
            window.blit(IMAGE_Pause_button_middle, LOST_RESTART[3])
            window.blit(IMAGE_Pause_button_right, LOST_RESTART[4])
            show_text('重新尝试', 18, FONT_COLOR[1], text_pos1)
    else:
        window.blit(IMAGE_Pause_button_left, LOST_RESTART[0])
        window.blit(IMAGE_Pause_button_middle, LOST_RESTART[1])
        window.blit(IMAGE_Pause_button_middle, LOST_RESTART[2])
        window.blit(IMAGE_Pause_button_middle, LOST_RESTART[3])
        window.blit(IMAGE_Pause_button_right, LOST_RESTART[4])
        show_text('重新尝试', 18, FONT_COLOR[0], text_pos1)


def on_card(pos):
    if pos[0] < CARD_POS[0] or pos[0] > CARD_POS[0] + CARD_SIZE[0] * len(card_list):
        return -1
    if pos[1] < CARD_POS[1] or pos[1] > CARD_POS[1] + CARD_SIZE[1]:
        return -1
    return (pos[0] - CARD_POS[0]) // CARD_SIZE[0]


def minigames_on_grass(pos):
    if GRASS_POS[0] + 4 * GRASS_SIZE[0] <= pos[0] <= GRASS_POS[0] + GRASS_NUM[0] * GRASS_SIZE[0]:
        if GRASS_POS[1] <= pos[1] <= GRASS_POS[1] + GRASS_NUM[1] * GRASS_SIZE[1]:
            return True
    return False


def on_grass(pos):
    if GRASS_POS[0] <= pos[0] <= GRASS_POS[0] + GRASS_NUM[0] * GRASS_SIZE[0]:
        if GRASS_POS[1] <= pos[1] <= GRASS_POS[1] + GRASS_NUM[1] * GRASS_SIZE[1]:
            return True
    return False


def on_adventure_button(pos):
    if 390 <= pos[0] <= 648 and 102 <= pos[1] <= 198:
        return True
    if 606 <= pos[0] <= 743 and 123 <= pos[1] <= 215:
        return True
    return False


def on_minigames_button(pos):
    if 413 <= pos[0] <= 558 and 193 <= pos[1] <= 251:
        return True
    if 490 <= pos[0] <= 568 and 203 <= pos[1] <= 261:
        return True
    if 561 <= pos[0] <= 625 and 212 <= pos[1] <= 274:
        return True
    if 625 <= pos[0] <= 707 and 224 <= pos[1] <= 292:
        return True
    return False


def on_help_button(pos):
    if 655 <= pos[0] <= 697 and 530 <= pos[1] <= 552:
        return True
    return False


def on_quit_button(pos):
    if 731 <= pos[0] <= 776 and 513 <= pos[1] <= 537:
        return True
    return False


def on_options_button(pos):
    if 572 <= pos[0] <= 628 and 492 <= pos[1] <= 517:
        return True
    return False


def on_shovel(pos):
    if GRASS_POS[0] + 7 * GRASS_SIZE[0] <= pos[0] <= GRASS_POS[0] + 8 * GRASS_SIZE[0] and pos[1] <= 80:
        return True
    return False


def on_pause(pos):
    # if 700 <= pos[0] <= 812 and pos[1] <= 32:
    if 680 <= pos[0] <= 792 and pos[1] <= 32:
        return True
    return False


def on_sun(sun_list, pos):
    for each in sun_list:
        if each.posx + 10 <= pos[0] <= each.posx + 70 and each.posy + 10 <= pos[1] <= each.posy + 70:
            sun_list.remove(each)
            return each
    return None


def can_grow(pos):
    for each in GRASS:
        if GRASS_POS[0] + each.grassx * GRASS_SIZE[0] <= pos[0] <= GRASS_POS[0] + (each.grassx + 1) * GRASS_SIZE[0]:
            if GRASS_POS[1] + each.grassy * GRASS_SIZE[1] <= pos[1] <= GRASS_POS[1] + (each.grassy + 1) * GRASS_SIZE[1]:
                return False
    return True


def remove_plant_in_GRASS(plant):
    for each in GRASS:
        if each.grassx == plant.grassx and each.grassy == plant.grassy:
            GRASS.remove(each)
            return


def exist_plant(pos):
    grass_x = (pos[0] - GRASS_POS[0]) // GRASS_SIZE[0]
    grass_y = (pos[1] - GRASS_POS[1]) // GRASS_SIZE[1]
    for plant in plant_list:
        if plant.grassx == grass_x and plant.grassy == grass_y:
            plant_list.remove(plant)
            remove_plant_in_GRASS(plant)
            return True
    return False


def check_collision(bullet_x, grassy, healthdecrease):
    for each in zombie_list:
        if each.grassy == grassy:
            if -50 < each.posx - bullet_x < -40:
                each.hurt(healthdecrease)
                return each
    return None


def minigames_check_collision(bullet_x, grassy, healthdecrease):
    for each in zombie_list:
        if each.grassy == grassy + 1:
            if -50 < each.posx - bullet_x < -40:
                each.hurt(healthdecrease)
                return each
    return None


def check_eat(posx, posy):
    grassy = (posy - GRASS_POS[1]) // GRASS_SIZE[1]
    for each in plant_list:
        if grassy == each.grassy - 1:
            if each.code != -1:
                temp_grassx = GRASS_POS[0] + each.grassx * GRASS_SIZE[0] - 40
                if -25 <= posx - temp_grassx <= 5:
                    return each
            else:
                if -95 <= posx <= -65:
                    return each
    return None


def minigames_discover_zombie(grassx, grassy):
    for each in zombie_list:
        if each.grassy == grassy + 1 and each.posx > GRASS_POS[0] + grassx * GRASS_SIZE[0]:
            return True
    return False


def discover_zombie(grassx, grassy):
    for each in zombie_list:
        if each.grassy == grassy and each.posx > GRASS_POS[0] + grassx * GRASS_SIZE[0] and not each.boom:
            return True
    return False


def plant_object(card_num, move, pos=None):
    if card_num == 0:
        return Peashooter(move, pos)
    if card_num == 1:
        return Sunflower(move, pos)
    if card_num == 2:
        return CherryBomb(move, pos)
    if card_num == 3:
        return WallNut(move, pos)
    if card_num == 4:
        return PotatoMine(move, pos)
    if card_num == 5:
        return DoublePea(move, pos)
    if card_num == 6:
        return Chomper(move, pos)


def find_moving_card(code):
    for each in moving_card_list:
        if each.code == code:
            return True
    return False


def zombie_object(zombie_num, posx=None, grassy =None):
    if zombie_num == 0:
        return NormalZombie( posx=posx, grassy=grassy)
    if zombie_num == 1:
        return ConeHeadZombie(posx=posx, grassy=grassy)
    if zombie_num == 2:
        return BucketHeadZombie(posx=posx,grassy=grassy)


def minigames_zombie_object(zombie_num, move, pos=None):
    if pos is not None:
        # temp_pos = (pos[0], (pos[1] - GRASS_POS[1]) // GRASS_SIZE[1])
        if zombie_num == 0:
            return MinigamesNormalZombie(move, initpos=pos)
        if zombie_num == 1:
            return MinigamesConeZombie(move, initpos=pos)
        if zombie_num == 2:
            return MinigamesBucketZombie(move, initpos=pos)

    else:
        if zombie_num == 0:
            return MinigamesNormalZombie(move)
        if zombie_num == 1:
            return MinigamesConeZombie(move)
        if zombie_num == 2:
            return MinigamesBucketZombie(move)


def check_files():

    load_font_flag = True
    while load_font_flag:
        try:
            file = pygame.font.Font(FONT, 32)
        except FileNotFoundError:
            loading = True
            while loading:
                font = pygame.font.SysFont('华文宋体', 32)
                text1 = font.render('字体文件gbk.ttf缺失  请检查文件完整性', True, (255, 255, 255))
                text2 = font.render('按a键重新加载', True, (255, 255, 255))
                window.blit(text1, (130, 200))
                window.blit(text2, (296, 400))
                pygame.display.update()
                for event in pygame.event.get():
                    if event.type == pygame.QUIT:
                        pygame.quit()
                        exit()
                    if event.type == pygame.KEYDOWN:
                        if chr(event.key) == 'a':
                            loading = False
        else:
            load_font_flag = False


    load_image_flag = True
    while load_image_flag:
        try:
            load_images()
        except FileNotFoundError:
            text1 = '图片文件或images目录缺失  请检查文件完整性'
            text2 = '按a键重新加载'
            loading = True
            while loading:
                window.fill((0, 0, 0))
                show_text(text1, 32, (255, 255, 255), (70, 200), text2, (296, 400))
                pygame.display.update()
                for event in pygame.event.get():
                    if event.type == pygame.QUIT:
                        pygame.quit()
                        exit()
                    if event.type == pygame.KEYDOWN:
                        if chr(event.key) == 'a':
                            loading = False
        else:
            load_image_flag = False

    load_music_flag = True
    while load_music_flag:
        try:
            load_music(0, 0, loading_flag=True)
        except pygame.error:
            text1 = '音乐文件或music目录缺失  请检查文件完整性'
            text2 = '按a键重新加载'
            loading = True
            while loading:
                window.fill((0, 0, 0))
                show_text(text1, 32, (255, 255, 255), (70, 200), text2, (296, 400))
                pygame.display.update()
                for event in pygame.event.get():
                    if event.type == pygame.QUIT:
                        pygame.quit()
                        exit()
                    if event.type == pygame.KEYDOWN:
                        if chr(event.key) == 'a':
                            loading = False
        else:
            load_music_flag = False

    load_sound_flag = True
    while load_sound_flag:
        try:
            for i in range(38):
                load_sound(0, loading_flag=True)
        except FileNotFoundError:
            text1 = '音效文件或sound目录缺失  请检查文件完整性'
            text2 = '按a键重新加载'
            loading = True
            while loading:
                window.fill((0, 0, 0))
                show_text(text1, 32, (255, 255, 255), (72, 200), text2, (296, 400))
                pygame.display.update()
                for event in pygame.event.get():
                    if event.type == pygame.QUIT:
                        pygame.quit()
                        exit()
                    if event.type == pygame.KEYDOWN:
                        if chr(event.key) == 'a':
                            loading = False
        else:
            load_sound_flag = False
    return

# 选小游戏True 主菜单False
def minigames_screen():

    load_music(2, -1)

    minigames_flag = True
    while minigames_flag:
        clock.tick(FPS)

        window.blit(IMAGE_Minigames_back, POS)
        window.blit(IMAGE_Minigames_imzombie, (59, 108))

        show_text('迷你游戏', 32, FONT_COLOR[6], (336, 32))

        temp_pos = pygame.mouse.get_pos()
        if 22 <= temp_pos[0] <= 258 and 98 <= temp_pos[1] <= 338:
            window.blit(IMAGE_Minigames_window_alpha, (22, 98))
        else:
            window.blit(IMAGE_Minigames_window, (22, 98))
        show_text('我是僵尸', 32, FONT_COLOR[7], (74, 264))

        if 22 <= temp_pos[0] <= 181 and 570 <= temp_pos[1] <= 591:
            window.blit(IMAGE_Minigames_back_button_alpha, (20, 568))
        else:
            window.blit(IMAGE_Minigames_back_button, (20, 568))
        show_text('主菜单', 16, FONT_COLOR[7], (79, 573))

        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit()
                exit()

            if event.type == pygame.MOUSEBUTTONDOWN:
                global mode_num
                if 22 <= event.pos[0] <= 258 and 98 <= event.pos[1] <= 338:
                    mode_num = 1
                    load_sound(36)
                    return True

                elif 22 <= event.pos[0] <= 181 and 570 <= event.pos[1] <= 591:
                    load_sound(36)
                    return False

        pygame.display.update()
    return False


def start(fromscreen=None):

    load_music(0, -1)

    on_click = 0

    stay_on_button_flag = 0

    start_flag = True
    while start_flag:
        clock.tick(FPS)

        set_background(0)
        adventure = Button(ADVENTURE_BUTTON, IMAGE_Adventure_button, IMAGE_Adventure_button_alpha)
        minigames = Button(MINIGAMES_BUTTON, IMAGE_Minigames_button, IMAGE_Minigames_button_alpha)

        help_button = Button(HELP_BUTTON, IMAGE_Help_button, IMAGE_Help_button_alpha)
        quit_button = Button(QUIT_BUTTON, IMAGE_Quit_button, IMAGE_Quit_button_alpha)
        options_button = Button(OPTIONS_BUTTON, IMAGE_Options_button, IMAGE_Options_button_alpha)

        temp_pos = pygame.mouse.get_pos()
        set_background(0)
        if on_adventure_button(temp_pos):
            if stay_on_button_flag != 1:
                load_sound(15)
                stay_on_button_flag = 1
            adventure.draw_alpha()
        else:
            if stay_on_button_flag == 1:
                stay_on_button_flag = 0
            adventure.draw()

        if on_minigames_button(temp_pos):
            if stay_on_button_flag != 4:
                load_sound(15)
                stay_on_button_flag = 4
            minigames.draw_alpha()
        else:
            if stay_on_button_flag == 4:
                stay_on_button_flag = 0
            minigames.draw()

        if on_help_button(temp_pos):
            if stay_on_button_flag != 2:
                load_sound(15)
                stay_on_button_flag = 2
            help_button.draw_alpha()
        else:
            if stay_on_button_flag == 2:
                stay_on_button_flag = 0
            help_button.draw()

        if on_quit_button(temp_pos):
            if stay_on_button_flag != 3:
                load_sound(15)
                stay_on_button_flag = 3
            quit_button.draw_alpha()
        else:
            if stay_on_button_flag == 3:
                stay_on_button_flag = 0
            quit_button.draw()

        if on_options_button(temp_pos):
            if stay_on_button_flag != 5:
                load_sound(15)
                stay_on_button_flag = 5
            options_button.draw_alpha()
        else:
            if stay_on_button_flag == 5:
                stay_on_button_flag = 0
            options_button.draw()

        show_text('1', 25, (255, 255, 255), ADVENTURE_TEXT[0], str(LEVEL), ADVENTURE_TEXT[1])
        pygame.display.update()

        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit()
                exit()

            elif event.type == pygame.MOUSEBUTTONDOWN:
                if on_quit_button(event.pos):
                    on_click = 1
                elif on_adventure_button(event.pos):
                    on_click = 2
                elif on_help_button(event.pos):
                    on_click = 3
                elif on_minigames_button(event.pos):
                    on_click = 4
                elif on_options_button(event.pos):
                    on_click = 5

            elif event.type == pygame.MOUSEBUTTONUP:
                global mode_num
                if on_quit_button(event.pos) and on_click == 1:
                    load_sound(13)
                    pygame.quit()
                    exit()
                elif on_adventure_button(event.pos) and on_click == 2:
                    mode_num = 0
                    if fromscreen == 'imzombie':
                        return False
                    return True
                elif on_help_button(event.pos) and on_click == 3:
                    pygame.mixer.music.stop()
                    load_sound(13)
                    load_sound(16)
                    help_screen()
                    pygame.mixer.music.play()
                elif on_minigames_button(event.pos) and on_click == 4:
                    load_sound(17)
                    if minigames_screen():
                        mode_num = 1
                        if fromscreen == 'adventure':
                            return False
                        return True
                elif on_options_button(event.pos) and on_click == 5:
                    load_sound(13)
                    if not pause('start'):
                        mode_num = 0
                        return

                on_click = 0

    pygame.mixer.music.stop()


def pause(fromscreen=None):
    global MUSIC_POS
    MUSIC_POS = MUSIC_POS + pygame.mixer.music.get_pos() / 1000
    MUSIC_POS = MUSIC_POS % 300

    global MUSIC_VOLUME
    global SOUND_VOLUME

    pygame.mixer.music.pause()

    temp = window.copy()
    click_flag = 0

    pause_flag = True

    while pause_flag:
        clock.tick(FPS)
        window.blit(temp, POS)
        window.blit(IMAGE_Pause_screen, (194, 58))

        temp_pos = pygame.mouse.get_pos()

        set_back_to_game(click_flag, temp_pos)
        if fromscreen != 'start':
            set_option_up(click_flag, temp_pos)
        set_option_down(click_flag, temp_pos)

        posx = temp_pos[0]
        if posx < 400:
            posx = 400
        if posx > 535:
            posx = 535
        if click_flag == 4:
            MUSIC_VOLUME = (posx - 400) / 135
        elif click_flag == 5:
            SOUND_VOLUME = (posx - 400) / 135

        music_knob_pos = (390 + 135 * MUSIC_VOLUME, 190)
        sound_knob_pos = (390 + 135 * SOUND_VOLUME, 230)

        window.blit(IMAGE_Volume, (400, 200))
        window.blit(IMAGE_Volume_knob, music_knob_pos)
        window.blit(IMAGE_Volume, (400, 240))
        window.blit(IMAGE_Volume_knob, sound_knob_pos)
        show_text('音量：音乐', 20, FONT_COLOR[3], (290, 190), '音量：音效', (290, 230))

        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit()
                exit()

            elif event.type == pygame.MOUSEBUTTONDOWN:
                if event.button == 1:
                    if 244 <= event.pos[0] <= 556 and 452 <= event.pos[1] <= 520:
                        click_flag = 1
                    elif PAUSE_OPTION_UP[0][0] <= event.pos[0] <= PAUSE_OPTION_UP[4][0] + 35 and PAUSE_OPTION_DOWN[0][
                        1] <= event.pos[1] <= PAUSE_OPTION_DOWN[0][1] + 35:
                        click_flag = 2
                    elif PAUSE_OPTION_UP[0][0] <= event.pos[0] <= PAUSE_OPTION_UP[4][0] + 35 and PAUSE_OPTION_UP[0][
                        1] <= event.pos[1] <= PAUSE_OPTION_UP[0][1] + 46 and fromscreen != 'start':
                        click_flag = 3
                    elif music_knob_pos[0] <= event.pos[0] <= music_knob_pos[0] + 21 and music_knob_pos[1] + 3 <= \
                            event.pos[1] <= music_knob_pos[1] + 27:
                        # print(MUSIC_VOLUME)
                        click_flag = 4
                    elif sound_knob_pos[0] <= event.pos[0] <= sound_knob_pos[0] + 21 and sound_knob_pos[1] + 3 <= \
                            event.pos[1] <= sound_knob_pos[1] + 27:
                        # print(SOUND_VOLUME)
                        click_flag = 5

            elif event.type == pygame.MOUSEBUTTONUP:
                if event.button == 1 and click_flag != 0:
                    if 242 <= event.pos[0] <= 556 and 452 <= event.pos[1] <= 520:
                        load_sound(17)
                        pause_flag = False
                    elif PAUSE_OPTION_UP[0][0] <= event.pos[0] <= PAUSE_OPTION_UP[4][0] + 35:
                        if PAUSE_OPTION_DOWN[0][1] <= event.pos[1] <= PAUSE_OPTION_DOWN[0][1] + 35 and click_flag == 2:
                            pygame.quit()
                            exit()
                        if PAUSE_OPTION_UP[0][1] <= event.pos[1] <= PAUSE_OPTION_UP[0][1] + 46 and fromscreen != 'start' and click_flag == 3:
                            load_sound(17)
                            global mode_num
                            if fromscreen == 'choose' or fromscreen == 'adventure':
                                if not start('adventure'):
                                    mode_num = 1
                                    return False
                            if fromscreen == 'imzombie':
                                if not start('imzombie'):
                                    return False

                    click_flag = 0

        pygame.display.update()

    if fromscreen is None:
        load_music(1, -1, True)
    if fromscreen == 'start':
        load_music(0, -1, True)
    elif fromscreen == 'choose':
        load_music(2, -1, True)
    elif fromscreen == 'imzombie':
        load_music(4, -1, True)

    return True


# 0 无 1 按下回到游戏 2 按下退出游戏 3 按下主菜单


def fail_screen(fromscreen=None):
    pygame.mixer.music.stop()

    load_sound(21)

    temp_window = window.copy()

    click_flag = 0

    i = 1
    lost_flag = True

    global mode_num

    while lost_flag:
        clock.tick(FPS)
        if fromscreen is None and i <= 3 * FPS and i % FPS == 0:
            if i // FPS == 2:
                load_sound(20)
            load_sound(i // FPS)

        window.blit(temp_window, POS)
        if fromscreen is None and i <= 2 * FPS:
            temp_screen = pygame.transform.rotozoom(IMAGE_Lost, 0, i / (2 * FPS))
            wid, hei = temp_screen.get_size()
            temp_pos = ((WINDOW_WIDTH - wid) / 2, (WINDOW_HEIGHT - hei) / 2)
            window.blit(temp_screen, temp_pos)
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    pygame.quit()
                    exit()
        elif fromscreen is None and i <= 8 * FPS:
            temp_back = pygame.Surface(WINDOW_SIZE)
            temp_back.fill((0, 0, 0))
            temp_back.set_alpha(int((150 * i - 300 * FPS) / (6 * FPS)))

            window.blit(temp_back, POS)
            window.blit(IMAGE_Lost, (118, 66))
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    pygame.quit()
                    exit()
        else:
            temp_back = pygame.Surface(WINDOW_SIZE)
            temp_back.fill((0, 0, 0))
            temp_back.set_alpha(150)
            window.blit(temp_back, POS)
            window.blit(IMAGE_Dialog, (245, 182))
            show_text('游戏结束', 25, FONT_COLOR[2], (350, 280))

            temp_pos = pygame.mouse.get_pos()

            set_start_again(click_flag, temp_pos)
            set_pause_button(click_flag, temp_pos)

            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    pygame.quit()
                    exit()

                if event.type == pygame.MOUSEBUTTONDOWN:
                    if event.button == 1:
                        if LOST_RESTART[0][0] <= event.pos[0] <= LOST_RESTART[4][0] + 46 and LOST_RESTART[0][1] <= \
                                event.pos[1] <= LOST_RESTART[0][1] + 46:
                            click_flag = 1
                        elif PAUSE_BUTTON[0][0] <= event.pos[0] <= PAUSE_BUTTON[2][0] + 46:
                            click_flag = 2

                elif event.type == pygame.MOUSEBUTTONUP:
                    if event.button == 1 and click_flag != 0:
                        if LOST_RESTART[0][0] <= event.pos[0] <= LOST_RESTART[4][0] + 204 and LOST_RESTART[0][1] <= \
                                event.pos[1] <= LOST_RESTART[0][1] + 46:
                            load_sound(17)
                            return True

                        elif PAUSE_BUTTON[0][0] <= event.pos[0] <= PAUSE_BUTTON[2][0] + 46 and PAUSE_BUTTON[0][1] <= \
                                event.pos[1] <= PAUSE_BUTTON[0][1] + 46:
                            load_sound(17)
                            if fromscreen == 'adventure':
                                if not start('adventure'):
                                    mode_num = 1
                                    return False
                            if fromscreen == 'imzombie':
                                if not start('imzombie'):
                                    mode_num = 0
                                    return False
                            return True

                        click_flag = 0

        pygame.display.update()
        # 0 no 1 click restart 2 click menu
        i += 1


def winscreen():

    pygame.mixer.music.fadeout(1000)
    load_sound(35)

    timer = 0

    posx = trophy.posx
    posy = trophy.posy + 61

    while True:
        clock.tick(FPS)

        if timer <= 2 * FPS:
            trophy_image = IMAGE_Trophy_get

            temp_screen = pygame.Surface(WINDOW_SIZE)
            temp_screen.fill((255, 255, 255))
            temp_screen.set_alpha(int(50 * timer / (7 * FPS)))
            window.blit(temp_screen, POS)

            window.blit(trophy_image, (posx, posy))

        elif timer <= 7 * FPS:
            trophy_image = IMAGE_Trophy_get

            window.blit(trophy_image, (posx, posy))

            temp_screen = pygame.Surface(WINDOW_SIZE)
            temp_screen.fill((255, 255, 255))
            temp_screen.set_alpha(int(255 * (timer - 2 * FPS) / (5 * FPS)))
            window.blit(temp_screen, POS)

        else:
            return

        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit()
                exit()

        pygame.display.update()
        timer += 1


def imzombie():
    load_music(4, -1)
    global card_list

    card_list = [0, 1, 2]

    mouse_zombie = None
    on_click = False

    global plant_list
    plant_list = [MinigamesSunflower(0, 0), MinigamesPeashooter(1, 0),
                  MinigamesSunflower(2, 0), MinigamesPeashooter(3, 0),
                  MinigamesWallNut(0, 1), MinigamesPeashooter(1, 1),
                  MinigamesSunflower(2, 1), MinigamesPeashooter(3, 1),
                  MinigamesChomper(0, 2), MinigamesSunflower(1, 2),
                  MinigamesPeashooter(2, 2), MinigamesSunflower(3, 2),
                  MinigamesSunflower(0, 3), MinigamesWallNut(1, 3),
                  MinigamesSunflower(2, 3), MinigamesDoublePea(3, 3),
                  MinigamesPotatoMine(0, 4), MinigamesSunflower(1, 4),
                  MinigamesPeashooter(2, 4), MinigamesSunflower(3, 4)]

    global brain_list
    brain_list = []

    for i in range(5):
        brain_list.append(i)
        plant_list.append(Brain(i))

    global bullets
    global zombie_list
    global head_list
    global body_list

    bullets = []
    zombie_list = []
    head_list = []
    body_list = []

    global sun_falling
    global sun_collecting
    sun_falling = []
    sun_collecting = []
    initsun = INIT_SUN

    pause_click_flag = 0

    global win
    win = False

    global trophy
    trophy = None

    global mode_num

    while True:
        clock.tick(FPS)

        set_background(3)
        window.blit(IMAGE_Zombie_line, (347, 71))

        set_sun(str(initsun))

        card_pos = CARD_POS
        for i in card_list:
            if initsun < ZOMBIE_SUN_COST[i]:
                set_minigames_card(i, card_pos, 'alpha')
            else:
                set_minigames_card(i, card_pos)
            card_pos = (card_pos[0] + CARD_SIZE[0], card_pos[1])

        for each in plant_list:
            if each.code == 0:
                temp_bullet = each.draw_on_grass()
                if temp_bullet is not None:
                    bullets.append(temp_bullet)
            elif each.code == 5:
                temp_bullet = each.draw_on_grass()
                if temp_bullet is not None:
                    bullets.append(temp_bullet)
            else:
                each.draw_on_grass()

        temp_pos = pygame.mouse.get_pos()

        for each in bullets:
            each.draw()
            each.move()

        for each in zombie_list:
            each.draw_on_grass()

        for each in head_list:
            each.draw()
        for each in body_list:
            each.draw()

        for each in sun_falling:
            each.draw()
            each.fall()
        for each in sun_collecting:
            each.draw()
            each.move()

        if trophy is not None:
            trophy.draw()
            trophy.fall()

        if initsun < 50 and len(brain_list) > 0 and len(zombie_list) == 0:
            fail_screen('imzombie')
            return

        set_pause_button(pause_click_flag, temp_pos)

        if on_click and mouse_zombie is not None:
            if minigames_on_grass(temp_pos):
                alpha = minigames_zombie_object(mouse_zombie.code, False)
                alpha.draw_alpha(temp_pos)
            temp_zombie_size = ZOMBIE_SIZE[mouse_zombie.code]
            temp_pos = (temp_pos[0] - temp_zombie_size[0] * 2 / 3, temp_pos[1] - temp_zombie_size[1] * 2 / 3)
            mouse_zombie.draw(temp_pos)

        pygame.display.update()

        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit()
                exit()

            elif event.type == pygame.MOUSEBUTTONDOWN:
                if event.button == 1:

                    card_flag = on_card(event.pos)
                    flag = on_sun(sun_falling, event.pos)

                    if on_pause(event.pos):
                        pause_click_flag = 2

                    elif flag is not None:
                        if not on_click:
                            temp_sun = SunCollecting((flag.posx, flag.posy))
                            sun_collecting.append(temp_sun)
                        initsun += Sun.sun_point

                    elif card_flag != -1:
                        if not on_click:
                            if initsun >= ZOMBIE_SUN_COST[card_list[card_flag]]:
                                load_sound(4)
                                mouse_zombie = minigames_zombie_object(card_list[card_flag], False)
                                on_click = True
                            else:
                                load_sound(14)
                        else:
                            load_sound(6)
                            on_click = False
                            mouse_zombie = None

                    elif minigames_on_grass(event.pos):
                        if on_click:
                            zombie_list.append(minigames_zombie_object(mouse_zombie.code, True, event.pos))
                            load_sound(0)
                            on_click = False
                            initsun -= ZOMBIE_SUN_COST[mouse_zombie.code]

                    if on_grass(event.pos):
                        if trophy is not None:
                            if trophy.posx + 5 < event.pos[0] < trophy.posx + 77 and trophy.posy + 54 < event.pos[1] < trophy.posy + 115:

                                winscreen()
                                if not start('imzombie'):
                                    mode_num = 0
                                    return

            elif event.type == pygame.MOUSEBUTTONUP:
                if event.button == 1 and pause_click_flag == 2:
                    if on_pause(event.pos):
                        load_sound(17)
                        load_sound(18)

                        if not pause('imzombie'):

                            mode_num = 0
                            return

                    pause_click_flag = 0



def game():
    # plant(code) sequence in card bank
    global card_list
    # card_list = [0, 1, 2, 3, 4, 5, 6]
    card_list = []

    mouse_plant = None
    on_click = False

    global plant_list
    plant_list = []

    global lawnmower_list
    lawnmower_list = []

    global bullets
    global zombie_list
    global head_list
    global body_list

    bullets = []
    zombie_list = []
    head_list = []
    body_list = []

    flagmeter = FlagMeter()

    global sun_falling
    global sun_collecting
    sun_falling = []
    sun_collecting = []
    initsun = INIT_SUN

    shovel_flag = False

    pause_click_flag = 0

    timer = 0

    last_selected_time = [4 * FPS, -3.5 * FPS, 4 * FPS, 4 * FPS, 4 * FPS, 4 * FPS, 4 * FPS]
    # last_selected_time = [-30 * FPS] * 7

    global moving_card_list
    moving_card_list = []

    selecting_flag = True

    speed_back = 380 / (1.5 * FPS)
    speed_cards_up = 87 / (0.2 * FPS)
    speed_cards_down = 513 / (0.2 * FPS)
    speed_pause_button = 46 / (0.2 * FPS)

    speed_cards_up_back = 10 / (1.5 * FPS)
    speed_shovel = 80 / (1.5 * FPS)

    global USEREVENT_COUNT

    SUN_FALL = pygame.USEREVENT + USEREVENT_COUNT
    USEREVENT_COUNT += 1

    pygame.time.set_timer(SUN_FALL, 3000)

    zombie_count = 0
    wave_count = 0

    huge_wave = 1e8
    final_wave = 1e8

    ZOMBIE_CRAETE = pygame.USEREVENT + USEREVENT_COUNT
    USEREVENT_COUNT += 1

    ZOMBIE_WAVE = pygame.USEREVENT + USEREVENT_COUNT
    USEREVENT_COUNT += 1

    for i in [4, 3, 2, 1, 0]:
        initpos = i * 20 - 160
        lawnmower = Lawnmower(initpos, i)
        lawnmower_list.append(lawnmower)

    global fail
    global win
    fail = False
    win = False

    global trophy
    trophy = None

    global GRASS
    GRASS = []

    global mode_num

    while True:
        clock.tick(FPS)
        # print(timer)
        # (0, 87) (22, 123) (75, 123)
        if selecting_flag:
            if timer == 0:
                load_music(2, -1)

            if timer <= 2 * FPS:
                set_background(2, POS)

            elif timer <= 3.5 * FPS:
                posx = POS[0] - speed_back * (timer - 2 * FPS)
                set_background(2, (posx, 0))

            elif timer <= 3.7 * FPS:
                posy_up = -87 + speed_cards_up * (timer - 3.5 * FPS)
                posy_down = 600 - speed_cards_down * (timer - 3.5 * FPS)
                posy_cards = 636 - speed_cards_down * (timer - 3.5 * FPS)
                posy_ready = 994 - speed_cards_down * (timer - 3.5 * FPS)
                posy_pause = PAUSE_BUTTON[0][1] - 46 + speed_pause_button * (timer - 3.5 * FPS)
                posy_text = -44 + speed_pause_button * (timer - 3.5 * FPS)

                set_sun(str(initsun), (0, posy_up))
                window.blit(IMAGE_Choose_seeds, (0, posy_down))

                window.blit(IMAGE_Ready_button[0], (76, posy_ready))
                show_text('开始战斗', 36, FONT_COLOR[4], (160, posy_down + 19))

                window.blit(IMAGE_Pause_button_left, (PAUSE_BUTTON[0][0], posy_pause))
                window.blit(IMAGE_Pause_button_middle, (PAUSE_BUTTON[1][0], posy_pause))
                window.blit(IMAGE_Pause_button_right, (PAUSE_BUTTON[2][0], posy_pause))
                show_text('菜单', 18, FONT_COLOR[0], (721, posy_text))

                card_pos = (22, posy_cards)
                for i in range(7):
                    set_cards(i, card_pos)
                    card_pos = (card_pos[0] + CARD_SIZE[0] + 2, posy_cards)

            else:
                set_background(2, (-380, 0))
                set_sun(str(initsun), (0, 0))
                window.blit(IMAGE_Choose_seeds, (0, 87))

                card_pos = (22, 123)
                for i in range(7):
                    if i not in card_list:
                        if not find_moving_card(i):
                            set_cards(i, card_pos)
                    card_pos = (card_pos[0] + CARD_SIZE[0] + 2, card_pos[1])

                card_pos = (80, 8)

                for i in range(len(card_list)):
                    if not find_moving_card(card_list[i]):
                        set_cards(card_list[i], card_pos)
                    card_pos = (card_pos[0] + CARD_SIZE[0], card_pos[1])

                for each in moving_card_list:
                    each.draw()
                    each.move()

                pos = pygame.mouse.get_pos()
                set_pause_button(pause_click_flag, pos)

                # print(pos)

                if len(card_list) < 7:
                    window.blit(IMAGE_Ready_button[0], (76, 481))
                    show_text('开始战斗', 36, FONT_COLOR[4], (160, 500))
                else:
                    window.blit(IMAGE_Ready_button[1], (76, 481))
                    if 80 <= pos[0] <= 383 and 486 <= pos[1] <= 552:
                        window.blit(IMAGE_Ready_button[2], (76, 481))
                    show_text('开始战斗', 36, FONT_COLOR[5], (160, 500))

                for event in pygame.event.get():
                    if event.type == pygame.QUIT:
                        pygame.quit()
                        exit()

                    elif event.type == pygame.MOUSEBUTTONDOWN:
                        if event.button == 1:

                            if on_pause(event.pos):
                                pause_click_flag = 2

                            card_pos = (22, 123)

                            if card_pos[1] <= event.pos[1] <= card_pos[1] + CARD_SIZE[1]:
                                if len(card_list) < 7:

                                    card_pos = (22, 123)

                                    for i in range(7):
                                        if i not in card_list:
                                            if card_pos[0] <= event.pos[0] <= card_pos[0] + CARD_SIZE[0] + 2:
                                                load_sound(6)

                                                endpos = (80 + len(card_list) * CARD_SIZE[0], 8)
                                                card_list.append(i)
                                                moving_card_list.append(MovingCard(card_image_list[i],
                                                                                   card_pos, endpos, i))
                                                break
                                        card_pos = (card_pos[0] + CARD_SIZE[0] + 2, card_pos[1])

                            if 80 <= event.pos[0] <= 383 and 486 <= event.pos[1] <= 552:
                                if len(card_list) == 7:
                                    load_sound(6)

                                    selecting_flag = False
                                    timer = 0
                                    continue

                            if 80 <= event.pos[0] <= 80 + len(card_list) * CARD_SIZE[0]:
                                if 8 <= event.pos[1] <= 8 + CARD_SIZE[1]:

                                    load_sound(6)

                                    removed_num = (event.pos[0] - 80) // CARD_SIZE[0]
                                    plant_removed = card_list[removed_num]

                                    card_pos = (80 + removed_num * CARD_SIZE[0], 8)
                                    endpos = (22 + plant_removed * (CARD_SIZE[0] + 2), 123)

                                    moving_card_list.append(MovingCard(card_image_list[plant_removed], card_pos,
                                                                       endpos, plant_removed))


                                    card_pos = (80 + (removed_num + 1) * CARD_SIZE[0], 8)
                                    endpos = (80 + removed_num * CARD_SIZE[0], 8)

                                    for i in range(removed_num + 1, len(card_list)):
                                        moving_card_list.append(MovingCard(card_image_list[card_list[i]],
                                                                           card_pos, endpos, card_list[i]))
                                        card_pos = (card_pos[0] + CARD_SIZE[0], 8)
                                        endpos = (endpos[0] + CARD_SIZE[0], 8)

                                    card_list.remove(plant_removed)
                                    # print(card_list)

                    elif event.type == pygame.MOUSEBUTTONUP:
                        if event.button == 1:
                            if pause_click_flag == 2:
                                if on_pause(event.pos):
                                    load_sound(17)
                                    load_sound(18)

                                    if not pause('choose'):

                                        mode_num = 1
                                        return
                                        # return False

                                pause_click_flag = 0

            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    pygame.quit()
                    exit()

            pygame.display.update()
            timer += 1
            continue


        if timer == 1:
            # pygame.mixer.music.stop()
            pygame.mixer.music.fadeout(1500)
        if timer == 1.5 * FPS:
            load_sound(31)

        if timer == 4 * FPS:

            load_music(1, -1)


        if timer <= 1.5 * FPS:
            posx = -380 + speed_back * timer
            set_background(2, (posx, 0))
        else:

            set_background(1)

            for each in lawnmower_list:
                each.draw()
                each.move()

        if timer <= 0.2 * FPS:

            posy_down = 87 + speed_cards_down * timer
            posy_cards = 123 + speed_cards_down * timer
            posy_ready = 481 + speed_cards_down * timer

            window.blit(IMAGE_Choose_seeds, (0, posy_down))

            window.blit(IMAGE_Ready_button[0], (76, posy_ready))
            show_text('开始战斗', 36, FONT_COLOR[4], (160, posy_ready + 19))

            card_pos = (22, posy_cards)
            for i in range(7):
                if i not in card_list:
                    set_cards(i, card_pos)
                card_pos = (card_pos[0] + CARD_SIZE[0] + 2, posy_cards)

        if timer <= 1.5 * FPS:

            posx_up = speed_cards_up_back * timer
            posy_shovel = -80 + speed_shovel * timer

            set_sun(str(initsun), (posx_up, 0))
            set_shovel((GRASS_POS[0] + 7 * GRASS_SIZE[0], posy_shovel))
            window.blit(IMAGE_Shovel, (GRASS_POS[0] + 7 * GRASS_SIZE[0], posy_shovel))

            card_pos = (posx_up + 80, 8)

            # print(card_list)

            for i in card_list:
                set_cards(i, card_pos)
                temp_alpha = pygame.Surface(CARD_SIZE)
                temp_alpha.fill((0, 0, 0))
                temp_alpha.set_alpha(int(100 * timer / (1.5 * FPS)))
                window.blit(temp_alpha, card_pos)
                card_pos = (card_pos[0] + CARD_SIZE[0], card_pos[1])

            pygame.display.update()

        elif timer <= 4 * FPS:
            set_sun(str(initsun), SUN_POS)
            set_shovel((GRASS_POS[0] + 7 * GRASS_SIZE[0], 0))
            window.blit(IMAGE_Shovel, (GRASS_POS[0] + 7 * GRASS_SIZE[0], 0))

            card_pos = (90, 8)

            for i in card_list:
                set_cards(i, card_pos)
                temp_alpha = pygame.Surface(CARD_SIZE)
                temp_alpha.fill((0, 0, 0))
                temp_alpha.set_alpha(100)
                window.blit(temp_alpha, card_pos)
                card_pos = (card_pos[0] + CARD_SIZE[0], card_pos[1])

            if 1.5 * FPS <= timer <= 2 * FPS:
                image_size = (int(28.8 + 1.92 * (timer - 1.5 * FPS)), int(11.55 + 0.77 * (timer - 1.5 * FPS)))
                ready_image = pygame.transform.scale(IMAGE_Start_planting[0], image_size)
                window.blit(ready_image, ((WINDOW_WIDTH - image_size[0]) / 2, (WINDOW_HEIGHT - image_size[1]) / 2))

            elif 2 * FPS < timer <= 2.5 * FPS:
                image_size = (int(24.45 + 1.63 * (timer - 2 * FPS)), int(14.4 + 0.96 * (timer - 2 * FPS)))
                set_image = pygame.transform.scale(IMAGE_Start_planting[1], image_size)
                window.blit(set_image, ((WINDOW_WIDTH - image_size[0]) / 2, (WINDOW_HEIGHT - image_size[1]) / 2))

            elif timer <= 3.5 * FPS:
                window.blit(IMAGE_Start_planting[2], (295, 251))

            pygame.display.update()
            if timer == 4 * FPS:
                pygame.time.set_timer(ZOMBIE_CRAETE, 5000)  # zombie_create_time()
                pygame.time.set_timer(ZOMBIE_WAVE, 60000)

        else:

            set_sun(str(initsun))

            set_shovel()

            flagmeter.draw()

            card_pos = CARD_POS
            for i in card_list:
                if initsun < SUN_COST[i]:
                    set_cards(i, card_pos, 'alpha')
                else:
                    set_cards(i, card_pos)
                set_frozen_time(i, last_selected_time[i], timer, card_pos)
                card_pos = (card_pos[0] + CARD_SIZE[0], card_pos[1])

            for each in plant_list:
                if each.code == 0:
                    temp_bullet = each.draw_on_grass()
                    if temp_bullet is not None:
                        bullets.append(temp_bullet)
                elif each.code == 5:
                    temp_bullet = each.draw_on_grass()
                    if temp_bullet is not None:
                        bullets.append(temp_bullet)
                else:
                    each.draw_on_grass()

            for each in lawnmower_list:
                each.draw()
                each.move()

            temp_pos = pygame.mouse.get_pos()

            if not shovel_flag:
                window.blit(IMAGE_Shovel, (GRASS_POS[0] + 7 * GRASS_SIZE[0], 0))
            else:
                window.blit(IMAGE_Shovel, (temp_pos[0] - 40, temp_pos[1] - 40))

            for each in bullets:
                each.draw()
                each.move()

            for each in zombie_list:
                each.draw()

            for each in head_list:
                each.draw()
            for each in body_list:
                each.draw()

            for each in sun_falling:
                each.draw()
                each.fall()
            for each in sun_collecting:
                each.draw()
                each.move()

            if trophy is not None:
                trophy.draw()
                trophy.fall()

            if fail:
                fail_screen()
                return

            if timer >= final_wave:
                if timer == final_wave:
                    load_sound(32)

                    for i in range(10):
                        zombie_list.append(zombie_object(random.randint(0, 2)))

                if 0 <= final_wave - timer <= FPS:
                    image_size = (int(341 + 34.1 * (timer - final_wave)), int(80 + 8 * (timer - final_wave)))
                    final_image = pygame.transform.scale(IMAGE_Final_wave, image_size)
                    window.blit(final_image, ((WINDOW_WIDTH - image_size[0])/2, (WINDOW_HEIGHT - image_size[1])/2))
                elif 0 <= timer - final_wave <= FPS * 1.5:
                    image_size = (341, 80)
                    final_image = pygame.transform.scale(IMAGE_Final_wave, image_size)
                    window.blit(final_image, ((WINDOW_WIDTH - image_size[0]) / 2, (WINDOW_HEIGHT - image_size[1]) / 2))

            if timer >= huge_wave:

                if timer - huge_wave == 1:
                    load_sound(33)
                if timer - huge_wave == FPS:
                    load_sound(37)

                    for i in range(10):
                        zombie_list.append(zombie_object(random.randint(0, 2)))

                if 0 <= huge_wave - timer <= FPS:
                    image_size = (int(492 + 49.2 * (timer - huge_wave)), int(80 + 8 * (timer - huge_wave)))
                    wave_image = pygame.transform.scale(IMAGE_Huge_wave, image_size)
                    window.blit(wave_image,
                                ((WINDOW_WIDTH - image_size[0]) / 2, (WINDOW_HEIGHT - image_size[1]) / 2))
                elif 0 <= timer - huge_wave <= FPS * 1.5:
                    image_size = (492, 80)
                    wave_image = pygame.transform.scale(IMAGE_Huge_wave, image_size)
                    window.blit(wave_image,
                                ((WINDOW_WIDTH - image_size[0]) / 2, (WINDOW_HEIGHT - image_size[1]) / 2))
                elif timer - huge_wave > FPS * 1.5:
                    huge_wave = 1e8

            set_pause_button(pause_click_flag, temp_pos)

            if on_click and mouse_plant is not None:
                if on_grass(temp_pos) and can_grow(temp_pos):
                    alpha = plant_object(mouse_plant.code, False)
                    alpha.draw_alpha(temp_pos)
                temp_plant_size = PLANT_SIZE[mouse_plant.code]
                temp_pos = (temp_pos[0] - temp_plant_size[0] // 2, temp_pos[1] - temp_plant_size[1] // 2)
                mouse_plant.draw(temp_pos)

            pygame.display.update()

            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    pygame.quit()
                    exit()

                elif event.type == SUN_FALL:
                    sun_falling.append(Sun())

                elif event.type == ZOMBIE_CRAETE:
                    if zombie_count == 0:
                        load_sound(34)
                    if zombie_count < TOTAL_ZOMBIE:
                        zombie_list.append(zombie_object(random.randint(0, 2)))
                        zombie_count += 1
                    if zombie_count == TOTAL_ZOMBIE:
                        win = True

                elif event.type == ZOMBIE_WAVE:
                    wave_count += 1

                    huge_wave = timer + 1
                    if wave_count == 2:
                        final_wave = timer + 66 * FPS

                elif event.type == pygame.MOUSEBUTTONDOWN:
                    if event.button == 1:

                        card_flag = on_card(event.pos)
                        flag = on_sun(sun_falling, event.pos)

                        if on_pause(event.pos):
                            pause_click_flag = 2

                        elif flag is not None:
                            if not on_click and not shovel_flag:
                                temp_sun = SunCollecting((flag.posx, flag.posy))
                                sun_collecting.append(temp_sun)
                            initsun += Sun.sun_point

                        elif card_flag != -1:
                            if not on_click:
                                if initsun >= SUN_COST[card_list[card_flag]] and timer - last_selected_time[card_list[card_flag]] >= FROZEN_TIME[card_list[card_flag]] * FPS:
                                    load_sound(4)
                                    mouse_plant = plant_object(card_list[card_flag], False)
                                    on_click = True
                                else:
                                    load_sound(14)
                            else:
                                load_sound(6)
                                on_click = False
                                mouse_plant = None

                        elif on_shovel(event.pos):
                            if not on_click:
                                if shovel_flag:
                                    load_sound(6)
                                    shovel_flag = False
                                else:
                                    load_sound(5)
                                    shovel_flag = True

                        elif on_grass(event.pos):
                            if on_click:
                                if can_grow(event.pos):
                                    last_selected_time[mouse_plant.code] = timer
                                    plant_list.append(plant_object(mouse_plant.code, True, event.pos))
                                    GRASS.append(PlantedGrass(event.pos, mouse_plant.code))
                                    load_sound(0)
                                    on_click = False
                                    initsun -= SUN_COST[mouse_plant.code]
                            elif shovel_flag:
                                if exist_plant(event.pos):
                                    load_sound(0)
                                    shovel_flag = False

                            elif trophy is not None:
                                if trophy.posx + 5 < event.pos[0] < trophy.posx + 77 and trophy.posy + 54 < event.pos[1] < trophy.posy + 115:
                                    winscreen()
                                    if not start('adventure'):
                                        mode_num = 1
                                    return
                                    # print('win')

                elif event.type == pygame.MOUSEBUTTONUP:
                    if event.button == 1 and pause_click_flag == 2:
                        if on_pause(event.pos):
                            load_sound(17)
                            load_sound(18)


                            if not pause('adventure'):
                                mode_num = 1
                                return

                        pause_click_flag = 0

        timer += 1


BACK_BUTTON = (325, 520)


def help_screen():
    help_flag = True
    while help_flag:
        clock.tick(FPS)

        back_button = Button(BACK_BUTTON, IMAGE_Back_button_alpha)

        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit()
                exit()

            if event.type == pygame.MOUSEBUTTONDOWN:
                if 325 <= event.pos[0] <= 480 and 520 <= event.pos[1] <= 562:
                    load_sound(13)
                    help_flag = False

        temp_pos = pygame.mouse.get_pos()
        window.blit(IMAGE_Help, POS)
        if 325 <= temp_pos[0] <= 480 and 520 <= temp_pos[1] <= 562:
            back_button.draw()

        pygame.display.update()


def choose_game(num):
    if num == 0:
        game()
    elif num == 1:
        imzombie()


def main():
    pygame.init()
    global clock
    clock = pygame.time.Clock()
    global mode_num
    mode_num = 0

    set_window('Python PVZ', WINDOW_SIZE)

    check_files()

    start()

    while True:
        choose_game(mode_num)


if __name__ == '__main__':
    main()
