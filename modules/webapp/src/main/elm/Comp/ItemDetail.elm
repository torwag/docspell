module Comp.ItemDetail exposing
    ( Model
    , emptyModel
    , update
    , view
    )

import Browser.Navigation as Nav
import Comp.ItemDetail.Model
import Comp.ItemDetail.Update exposing (Msg)
import Comp.ItemDetail.View exposing (..)
import Data.Flags exposing (Flags)
import Data.UiSettings exposing (UiSettings)
import Html exposing (..)
import Page exposing (Page(..))


type alias Model =
    Comp.ItemDetail.Model.Model


emptyModel : Model
emptyModel =
    Comp.ItemDetail.Model.emptyModel


update : Nav.Key -> Flags -> Maybe String -> Msg -> Model -> ( Model, Cmd Msg, Sub Msg )
update =
    Comp.ItemDetail.Update.update


view : { prev : Maybe String, next : Maybe String } -> UiSettings -> Model -> Html Msg
view =
    Comp.ItemDetail.View.view
